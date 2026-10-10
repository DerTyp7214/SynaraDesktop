package dev.dertyp.synara.sync

import dev.dertyp.data.ClientDevice
import dev.dertyp.data.ClientSetting
import dev.dertyp.data.ClientSettingConflict
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.ClientSettingsChanges
import dev.dertyp.data.ClientSettingsSnapshot
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IClientSettingsService
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.SettingsSyncKnown
import dev.dertyp.synara.db.SettingsSyncRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.utils.secureRandomBytes
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DEVICE = "device-local"
private const val OTHER_DEVICE = "device-other"
private const val USER = "user-1"
private const val PLATFORM = "test-platform"
private const val SECRET_KEY = "secret.token"
private const val FLAG_KEY = "secrets.flag"

private enum class ServerCall { REGISTER_DEVICE, GET_SNAPSHOT, GET_CHANGES, SET_SETTINGS, GET_DEVICES, DELETE_DEVICE, OBSERVE }

private data class WriteCall(
    val entries: List<ClientSettingWrite>,
    val scope: ClientSettingScope,
    val device: String?,
    val force: Boolean
)

private data class Registration(val deviceId: String, val name: String, val platform: String)

private fun settingEntry(
    key: String,
    value: String?,
    version: Long,
    device: String? = OTHER_DEVICE,
    scope: ClientSettingScope = ClientSettingScope.SYNCED
) = ClientSetting(
    key = key,
    value = value,
    deleted = value == null,
    version = version,
    modifiedAt = version,
    modifiedByDeviceId = device,
    scope = scope
)

private class FakeClientSettingsServer : IClientSettingsService {
    val stored = linkedMapOf<String, ClientSetting>()
    val deviceEntries = mutableListOf<ClientSetting>()
    var version = 0L
    val changes = MutableSharedFlow<ClientSettingsChange>(extraBufferCapacity = 64)

    val log = mutableListOf<ServerCall>()
    val registrations = mutableListOf<Registration>()
    val writes = mutableListOf<WriteCall>()
    val changeRequests = mutableListOf<Long>()
    val deletedDevices = mutableListOf<String>()
    val scriptedPages = ArrayDeque<ClientSettingsChanges>()
    val writeGates = ArrayDeque<CompletableDeferred<Unit>>()
    val streamEnds = ArrayDeque<CompletableDeferred<Unit>>()
    var devices = emptyList<ClientDevice>()
    var snapshotCalls = 0
    var snapshotFailures = 0
    var registerFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var changesFailure: Throwable? = null
    var devicesFailure: Throwable? = null
    var writeHandler: ((WriteCall) -> ClientSettingsWriteResult)? = null

    fun seed(key: String, value: String?, at: Long): ClientSetting {
        val entry = settingEntry(key, value, at)
        stored[key] = entry
        version = maxOf(version, at)
        return entry
    }

    fun foreignWrite(key: String, value: String?): ClientSetting = seed(key, value, version + 1)

    override suspend fun getSettings(
        scope: ClientSettingScope,
        device: String?,
        includeDeleted: Boolean
    ): List<ClientSetting> = throw UnsupportedOperationException()

    override suspend fun getSnapshot(deviceId: String): ClientSettingsSnapshot {
        log += ServerCall.GET_SNAPSHOT
        snapshotCalls++
        if (snapshotFailures > 0) {
            snapshotFailures--
            throw IllegalStateException("snapshot unavailable")
        }
        return ClientSettingsSnapshot(
            deviceId = deviceId,
            syncedVersion = version,
            deviceVersion = 0L,
            entries = stored.values.filterNot { it.deleted } + deviceEntries
        )
    }

    override suspend fun getChanges(
        scope: ClientSettingScope,
        sinceVersion: Long,
        device: String?,
        limit: Int
    ): ClientSettingsChanges {
        log += ServerCall.GET_CHANGES
        changeRequests += sinceVersion
        changesFailure?.let { throw it }
        scriptedPages.removeFirstOrNull()?.let { return it }
        return ClientSettingsChanges(
            scope = scope,
            version = version,
            entries = stored.values.filter { it.version > sinceVersion }.sortedBy { it.version }
        )
    }

    override suspend fun setSettings(
        entries: List<ClientSettingWrite>,
        scope: ClientSettingScope,
        device: String?,
        force: Boolean
    ): ClientSettingsWriteResult {
        log += ServerCall.SET_SETTINGS
        val call = WriteCall(entries, scope, device, force)
        writes += call
        writeGates.removeFirstOrNull()?.await()
        writeFailure?.let { throw it }
        writeHandler?.let { return it(call) }
        if (!force) {
            val conflicts = entries.mapNotNull { write ->
                val existing = stored[write.key]
                val accepted = when {
                    existing == null -> write.baseVersion == 0L
                    existing.deleted -> write.baseVersion == 0L || write.baseVersion == existing.version
                    else -> write.baseVersion == existing.version
                }
                if (accepted) null else ClientSettingConflict(write.key, write.baseVersion, existing)
            }
            if (conflicts.isNotEmpty()) return ClientSettingsWriteResult.Conflict(version, conflicts)
        }
        val applicable = entries.filter { write ->
            val existing = stored[write.key]
            write.value != null || (existing != null && !existing.deleted)
        }
        if (applicable.isEmpty()) return ClientSettingsWriteResult.Ok(version, emptyList())
        version++
        val written = applicable.map { write ->
            settingEntry(write.key, write.value, version, device, scope).also { stored[write.key] = it }
        }
        changes.tryEmit(
            ClientSettingsChange(scope = scope, version = version, keys = written.map { it.key }, modifiedByDeviceId = device)
        )
        return ClientSettingsWriteResult.Ok(version, written)
    }

    override suspend fun getHistory(
        scope: ClientSettingScope,
        key: String,
        device: String?,
        limit: Int
    ): List<ClientSetting> = throw UnsupportedOperationException()

    override suspend fun restore(
        scope: ClientSettingScope,
        key: String,
        version: Long,
        device: String?,
        force: Boolean
    ): ClientSettingsWriteResult = throw UnsupportedOperationException()

    override fun observeSettings(): Flow<ClientSettingsChange> {
        log += ServerCall.OBSERVE
        val end = streamEnds.removeFirstOrNull() ?: return changes
        return channelFlow {
            val forwarding = launch { changes.collect { send(it) } }
            end.await()
            forwarding.cancel()
        }
    }

    override suspend fun getDevices(): List<ClientDevice> {
        log += ServerCall.GET_DEVICES
        devicesFailure?.let { throw it }
        return devices
    }

    override suspend fun registerDevice(deviceId: String, name: String, platform: String): ClientDevice {
        log += ServerCall.REGISTER_DEVICE
        registrations += Registration(deviceId, name, platform)
        registerFailure?.let { throw it }
        return ClientDevice(deviceId, name, platform, 0L, 0L)
    }

    override suspend fun deleteDevice(deviceId: String) {
        log += ServerCall.DELETE_DEVICE
        deletedDevices += deviceId
        devices = devices.filterNot { it.deviceId == deviceId }
    }
}

private class InMemorySettingsSyncRepository : SettingsSyncRepository {
    val entries = mutableMapOf<String, SettingsSyncKnown>()
    var getAllCalls = 0
    var clearCalls = 0
    var putAllFailure: Throwable? = null

    override suspend fun getAll(): Map<String, SettingsSyncKnown> {
        getAllCalls++
        return entries.toMap()
    }

    override suspend fun putAll(entries: Map<String, SettingsSyncKnown>) {
        putAllFailure?.let {
            putAllFailure = null
            throw it
        }
        this.entries.putAll(entries)
    }

    override suspend fun remove(keys: Collection<String>) {
        keys.forEach { entries.remove(it) }
    }

    override suspend fun clear() {
        clearCalls++
        entries.clear()
    }
}

private class LocalSetting<T>(
    val key: String,
    group: SyncGroup,
    default: T,
    encoder: (T) -> String?,
    decoder: (String) -> T?
) {
    val flow = MutableStateFlow(default)
    var setterCalls = 0
    val setting = SyncedSetting(key, group, flow, default, encoder, decoder) {
        setterCalls++
        flow.value = it
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class SettingsSyncServiceTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val server = FakeClientSettingsServer()
    private val settings = InMemorySettings()
    private val repository = InMemorySettingsSyncRepository()
    private val cipher = spyk(SecretsCipher(settings))
    private val reachable = MutableStateFlow(true)
    private val connection = MutableStateFlow(ConnectionState.Authenticated)
    private val locals = mutableListOf<LocalSetting<*>>()
    private val identity = mockk<DeviceIdentity>()
    private val registry = mockk<SyncedSettingsRegistry>()
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private var userId: String? = USER
    private var currentDeviceName = "Desk"
    private lateinit var service: SettingsSyncService

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsSettingsSyncEnabled(true)
        Config.setIsSecretsSyncEnabled(false)
        Config.setQueueSyncDeviceName("")

        every { identity.deviceId } returns DEVICE
        every { identity.platform } returns PLATFORM
        every { identity.platformDeviceName } returns "Platform"
        every { identity.deviceName() } answers { currentDeviceName }

        every { registry.all } answers { locals.map { it.setting } }
        every { registry.general } answers { locals.map { it.setting }.filter { it.group == SyncGroup.GENERAL } }
        every { registry.secrets } answers { locals.map { it.setting }.filter { it.group == SyncGroup.SECRETS } }
        every { registry.byKey } answers { locals.map { it.setting }.associateBy { it.key } }
        every { registry.changes() } answers { locals.map { local -> local.flow.drop(1).map { } }.merge() }

        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection
        every { rpcServiceManager.userId } answers { userId }

        service = SettingsSyncService(
            clientSettingsService = server,
            identity = identity,
            settings = settings,
            repository = repository,
            registry = registry,
            cipher = cipher,
            rpcServiceManager = rpcServiceManager,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = TestDispatchers(dispatcher)
        )
    }

    @AfterTest
    fun tearDown() {
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun <T> register(local: LocalSetting<T>): LocalSetting<T> {
        locals += local
        return local
    }

    private fun string(key: String, group: SyncGroup = SyncGroup.GENERAL): LocalSetting<String> = register(
        LocalSetting(
            key = key,
            group = group,
            default = "",
            encoder = { AppJson.encodeToString(it) },
            decoder = { AppJson.decodeFromString<String>(it) }
        )
    )

    private fun nullableString(key: String): LocalSetting<String?> = register(
        LocalSetting(
            key = key,
            group = SyncGroup.GENERAL,
            default = null,
            encoder = { value -> value?.let { AppJson.encodeToString(it) } },
            decoder = { AppJson.decodeFromString<String>(it) }
        )
    )

    private fun secret(key: String): LocalSetting<String> = register(
        LocalSetting(
            key = key,
            group = SyncGroup.SECRETS,
            default = "",
            encoder = { value -> value.takeIf { it.isNotEmpty() }?.let { cipher.encryptToJson(key, it) } },
            decoder = { cipher.decryptFromJson(key, it) }
        )
    )

    private fun json(value: String): String = AppJson.encodeToString(value)

    private fun start() {
        service.start()
        scheduler.runCurrent()
    }

    private fun advance(duration: Duration) {
        scheduler.advanceTimeBy(duration)
        scheduler.runCurrent()
    }

    private fun <T> change(local: LocalSetting<T>, value: T) {
        local.flow.value = value
        scheduler.runCurrent()
    }

    private fun debounce() = advance(SettingsSyncService.OUTBOUND_DEBOUNCE)

    private fun announce(
        version: Long = server.version,
        device: String? = OTHER_DEVICE,
        scope: ClientSettingScope = ClientSettingScope.SYNCED
    ) {
        server.changes.tryEmit(ClientSettingsChange(scope = scope, version = version, keys = emptyList(), modifiedByDeviceId = device))
        scheduler.runCurrent()
    }

    private fun persist(version: Long, known: Map<String, SettingsSyncKnown>, user: String? = USER) {
        settings.put(SettingKey.SettingsSyncUserId, user)
        settings.put(SettingKey.SettingsSyncVersion, version)
        repository.entries.putAll(known)
    }

    private fun persistedVersion(): Long? = settings.getLongOrNull(SettingKey.SettingsSyncVersion.name)

    private fun setPassphrase(passphrase: String): Boolean {
        val result = CoroutineScope(dispatcher).async { service.setPassphrase(passphrase) }
        scheduler.runCurrent()
        return result.getCompleted()
    }

    private fun fastMeta(passphrase: String): Pair<SecretsMeta, DerivedKey> {
        val scratch = SecretsCipher(InMemorySettings())
        val draft = SecretsMeta(
            iterations = 1,
            salt = Base64.encode(secureRandomBytes(16)),
            check = SecretEnvelope(iv = "", ct = "", mac = "")
        )
        val key = scratch.deriveKey(passphrase, draft)
        return draft.copy(check = scratch.encrypt(SecretsCipher.META_KEY, SecretsCipher.CHECK_PLAINTEXT, key)) to key
    }

    private fun envelope(keyName: String, plaintext: String, key: DerivedKey): String =
        AppJson.encodeToString(SecretsCipher(InMemorySettings()).encrypt(keyName, plaintext, key))

    private fun seedMeta(meta: SecretsMeta): ClientSetting =
        server.seed(SecretsCipher.META_KEY, AppJson.encodeToString(meta), server.version + 1)

    private fun startWithConflict(): Pair<LocalSetting<String>, ClientSetting> {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        val serverEntry = server.seed("a", json("v2"), 7)
        server.devices = listOf(ClientDevice(OTHER_DEVICE, "Laptop", PLATFORM, 0L, 0L))
        setting.flow.value = "v3"
        start()
        return setting to serverEntry
    }

    private fun startFirstEnableWithDifference(): Triple<LocalSetting<String>, LocalSetting<String>, LocalSetting<String>> {
        val both = string("a")
        val serverOnly = string("b")
        val localOnly = string("c")
        server.seed("a", json("theirs"), 1)
        server.seed("b", json("b-server"), 2)
        server.devices = listOf(ClientDevice(OTHER_DEVICE, "Laptop", PLATFORM, 0L, 0L))
        both.flow.value = "mine"
        localOnly.flow.value = "c-mine"
        start()
        return Triple(both, serverOnly, localOnly)
    }

    @Test
    fun disabledSyncCallsNothingAndEnablingRegistersTheDeviceThenReadsTheSnapshot() {
        Config.setIsSettingsSyncEnabled(false)
        start()
        assertTrue(server.log.isEmpty())

        Config.setIsSettingsSyncEnabled(true)
        scheduler.runCurrent()

        assertEquals(listOf(ServerCall.REGISTER_DEVICE, ServerCall.GET_SNAPSHOT), server.log.take(2))
        assertEquals(listOf(Registration(DEVICE, "Desk", PLATFORM)), server.registrations)
    }

    @Test
    fun failingSnapshotIsRetriedAfterOneAndTwoSecondsAndThenReconciled() {
        val setting = string("a")
        server.seed("a", json("server"), 1)
        server.snapshotFailures = 2

        start()
        assertEquals(1, server.snapshotCalls)

        advance(999.milliseconds)
        assertEquals(1, server.snapshotCalls)
        advance(1.milliseconds)
        assertEquals(2, server.snapshotCalls)

        advance(1999.milliseconds)
        assertEquals(2, server.snapshotCalls)
        advance(1.milliseconds)
        assertEquals(3, server.snapshotCalls)
        assertEquals("server", setting.flow.value)
    }

    @Test
    fun freshDeviceWithDefaultLocalValueTakesTheServerValueWithoutWriting() {
        val setting = string("a")
        server.seed("a", json("server"), 1)

        start()

        assertEquals("server", setting.flow.value)
        assertEquals(SettingsSyncKnown(json("server"), 1), repository.entries["a"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun freshDeviceUploadsOnlyNonDefaultLocalValuesWithBaseVersionZero() {
        val changed = string("a")
        string("b")
        changed.flow.value = "mine"

        start()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("mine"), 0L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
    }

    @Test
    fun localChangeAgainstAnUnchangedServerEntryIsWrittenWithTheKnownBaseVersion() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        server.seed("a", json("v1"), 5)
        setting.flow.value = "v3"

        start()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("v3"), 5L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun serverChangeWithUnchangedLocalValueIsAppliedLocallyWithoutWriting() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        server.seed("a", json("v2"), 7)
        setting.flow.value = "v1"

        start()

        assertEquals("v2", setting.flow.value)
        assertEquals(SettingsSyncKnown(json("v2"), 7), repository.entries["a"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun sameChangeOnBothSidesAdoptsTheServerVersionWithoutWriteOrConflict() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        server.seed("a", json("v3"), 7)
        setting.flow.value = "v3"

        start()

        assertTrue(server.writes.isEmpty())
        assertNull(service.pendingConflict.value)
        assertEquals(SettingsSyncKnown(json("v3"), 7), repository.entries["a"])
        assertEquals(0, setting.setterCalls)
    }

    @Test
    fun differentChangesOnBothSidesRaiseAConflictKeepTheLocalValueAndKeepTheKeyOutOfLaterPushes() {
        val other = string("b")
        val (setting, serverEntry) = startWithConflict()

        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(serverEntry), conflict.entries)
        assertEquals("Laptop", conflict.deviceName)
        assertEquals("v3", setting.flow.value)
        assertTrue(server.writes.isEmpty())

        change(other, "b1")
        debounce()

        assertEquals(listOf(listOf(ClientSettingWrite("b", json("b1"), 0L))), server.writes.map { it.entries })
    }

    @Test
    fun snapshotSecretsGroupEntriesAreStoredButNotAppliedWhileLockedAndAppliedOnceUnlocked() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        val cipherText = envelope(SECRET_KEY, "remote-token", key)
        val entry = server.seed(SECRET_KEY, cipherText, server.version + 1)
        val flagEntry = server.seed(FLAG_KEY, json("remote-flag"), server.version + 1)
        val setting = secret(SECRET_KEY)
        val flag = string(FLAG_KEY, SyncGroup.SECRETS)
        Config.setIsSecretsSyncEnabled(true)

        start()

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertEquals("", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals("", flag.flow.value)
        assertEquals(0, flag.setterCalls)
        assertEquals(SettingsSyncKnown(cipherText, entry.version), repository.entries[SECRET_KEY])
        assertEquals(SettingsSyncKnown(json("remote-flag"), flagEntry.version), repository.entries[FLAG_KEY])

        assertTrue(setPassphrase("passphrase"))
        debounce()

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("remote-token", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertEquals("remote-flag", flag.flow.value)
        assertEquals(1, flag.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun pulledSecretsGroupEntriesAreRememberedButNotAppliedWhileLockedAndAppliedOnceUnlocked() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        val setting = secret(SECRET_KEY)
        val flag = string(FLAG_KEY, SyncGroup.SECRETS)
        Config.setIsSecretsSyncEnabled(true)
        start()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        val cipherText = envelope(SECRET_KEY, "remote-token", key)
        val entry = server.foreignWrite(SECRET_KEY, cipherText)
        val flagEntry = server.foreignWrite(FLAG_KEY, json("remote-flag"))

        announce()

        assertEquals(listOf(1L), server.changeRequests)
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertEquals("", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals("", flag.flow.value)
        assertEquals(0, flag.setterCalls)
        assertEquals(SettingsSyncKnown(cipherText, entry.version), repository.entries[SECRET_KEY])
        assertEquals(SettingsSyncKnown(json("remote-flag"), flagEntry.version), repository.entries[FLAG_KEY])
        assertEquals(3L, persistedVersion())

        assertTrue(setPassphrase("passphrase"))
        debounce()

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("remote-token", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertEquals("remote-flag", flag.flow.value)
        assertEquals(1, flag.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun pulledSecretsGroupEntriesAreAppliedAndRememberedWhileUnlocked() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        cipher.remember(key)
        val setting = secret(SECRET_KEY)
        val flag = string(FLAG_KEY, SyncGroup.SECRETS)
        Config.setIsSecretsSyncEnabled(true)
        start()
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        val cipherText = envelope(SECRET_KEY, "remote-token", key)
        val entry = server.foreignWrite(SECRET_KEY, cipherText)
        val flagEntry = server.foreignWrite(FLAG_KEY, json("remote-flag"))

        announce()

        assertEquals(listOf(1L), server.changeRequests)
        assertEquals("remote-token", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertEquals("remote-flag", flag.flow.value)
        assertEquals(1, flag.setterCalls)
        assertEquals(SettingsSyncKnown(cipherText, entry.version), repository.entries[SECRET_KEY])
        assertEquals(SettingsSyncKnown(json("remote-flag"), flagEntry.version), repository.entries[FLAG_KEY])
        assertEquals(3L, persistedVersion())

        debounce()
        advance(1.seconds)

        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun deviceScopedSnapshotEntriesAreIgnored() {
        val setting = string("a")
        server.deviceEntries += settingEntry("a", json("device"), 3, scope = ClientSettingScope.DEVICE)

        start()

        assertEquals("", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertNull(repository.entries["a"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun threeLocalChangesWithinTheDebounceWindowAreSentAsOneBatch() {
        val first = string("a")
        val second = string("b")
        val third = string("c")
        start()

        change(first, "1")
        advance(100.milliseconds)
        change(second, "2")
        advance(100.milliseconds)
        change(third, "3")

        advance(399.milliseconds)
        assertTrue(server.writes.isEmpty())
        advance(1.milliseconds)

        assertEquals(
            listOf(
                listOf(
                    ClientSettingWrite("a", json("1"), 0L),
                    ClientSettingWrite("b", json("2"), 0L),
                    ClientSettingWrite("c", json("3"), 0L)
                )
            ),
            server.writes.map { it.entries }
        )
    }

    @Test
    fun acceptedWriteAdvancesThePersistedVersionOnlyWhenItIsTheNextVersion() {
        val setting = string("a")
        start()

        change(setting, "one")
        debounce()
        assertEquals(1L, persistedVersion())

        repeat(3) { server.foreignWrite("unregistered", json("x$it")) }
        change(setting, "two")
        debounce()

        assertEquals(2, server.writes.size)
        assertEquals(5L, server.version)
        assertEquals(1L, persistedVersion())
    }

    @Test
    fun conflictOnOneKeyOfABatchBecomesAPendingConflictAndTheRetryCarriesTheOtherKey() {
        val first = string("a")
        val second = string("b")
        start()
        val remote = server.foreignWrite("a", json("remote"))

        first.flow.value = "mine"
        change(second, "b1")
        debounce()

        assertEquals(listOf(listOf("a", "b"), listOf("b")), server.writes.map { call -> call.entries.map { it.key } })
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(remote), conflict.entries)
        assertEquals("mine", first.flow.value)
    }

    @Test
    fun secondConflictInARowIsNotRetriedAndItsKeyStaysWritable() {
        val first = string("a")
        val second = string("b")
        start()
        val remote = settingEntry("a", json("remote"), 9L)
        var calls = 0
        server.writeHandler = { call ->
            calls++
            when (calls) {
                1 -> ClientSettingsWriteResult.Conflict(9L, listOf(ClientSettingConflict("a", 0L, remote)))
                2 -> ClientSettingsWriteResult.Conflict(9L, listOf(ClientSettingConflict("b", 0L, null)))
                else -> ClientSettingsWriteResult.Ok(
                    10L,
                    call.entries.map { settingEntry(it.key, it.value, 10L, DEVICE) }
                )
            }
        }

        first.flow.value = "mine"
        change(second, "b1")
        debounce()
        advance(1.seconds)

        assertEquals(
            listOf(
                listOf(ClientSettingWrite("a", json("mine"), 0L), ClientSettingWrite("b", json("b1"), 0L)),
                listOf(ClientSettingWrite("b", json("b1"), 0L))
            ),
            server.writes.map { it.entries }
        )
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(remote), conflict.entries)
        assertEquals(1, service.status.value.pendingKeys)
        assertNull(repository.entries["b"])

        change(second, "b2")
        debounce()

        assertEquals(3, server.writes.size)
        assertEquals(listOf(ClientSettingWrite("b", json("b2"), 0L)), server.writes[2].entries)
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)
        assertEquals(0, service.status.value.pendingKeys)
        assertEquals(SettingsSyncKnown(json("b2"), 10L), repository.entries["b"])
    }

    @Test
    fun conflictWhoseCurrentValueEqualsTheLocalValueOnlyAdoptsTheServerVersion() {
        val setting = string("a")
        start()
        server.foreignWrite("a", json("same"))

        change(setting, "same")
        debounce()

        assertEquals(1, server.writes.size)
        assertNull(service.pendingConflict.value)
        assertEquals(SettingsSyncKnown(json("same"), 1), repository.entries["a"])
    }

    @Test
    fun conflictWithoutACurrentEntryIsRetriedWithBaseVersionZero() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        server.seed("a", json("v1"), 5)
        setting.flow.value = "v1"
        start()
        server.stored.remove("a")

        change(setting, "new")
        debounce()

        assertEquals(
            listOf(
                listOf(ClientSettingWrite("a", json("new"), 5L)),
                listOf(ClientSettingWrite("a", json("new"), 0L))
            ),
            server.writes.map { it.entries }
        )
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun clearingANullableSettingWritesANullValueWithTheKnownBaseVersion() {
        val setting = nullableString("n")
        server.seed("n", json("de"), 1)
        start()
        assertEquals("de", setting.flow.value)

        change(setting, null)
        debounce()

        assertEquals(listOf(listOf(ClientSettingWrite("n", null, 1L))), server.writes.map { it.entries })
    }

    @Test
    fun remoteChangeAppliedByAPullIsNotEchoedBack() {
        val setting = string("a")
        start()
        server.foreignWrite("a", json("remote"))

        announce()
        assertEquals("remote", setting.flow.value)
        debounce()
        advance(1.seconds)

        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun unlockedUnchangedSecretIsNotResentWhenAnotherKeyChanges() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        server.seed(SECRET_KEY, envelope(SECRET_KEY, "token", key), server.version + 1)
        cipher.remember(key)
        Config.setIsSecretsSyncEnabled(true)
        val secretSetting = secret(SECRET_KEY)
        val general = string("a")
        secretSetting.flow.value = "token"
        start()
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertTrue(server.writes.isEmpty())

        change(general, "changed")
        debounce()

        assertEquals(listOf(listOf("a")), server.writes.map { call -> call.entries.map { it.key } })
    }

    @Test
    fun foreignNotificationPullsChangesSinceTheSyncedVersionAndADeletedEntryResetsToDefault() {
        val first = string("a")
        val second = string("b")
        server.seed("a", json("x"), 1)
        server.seed("b", json("y"), 2)
        start()
        assertEquals("y", second.flow.value)
        server.foreignWrite("a", json("x2"))
        server.foreignWrite("b", null)

        announce()

        assertEquals(listOf(2L), server.changeRequests)
        assertEquals("x2", first.flow.value)
        assertEquals("", second.flow.value)
        assertEquals(4L, persistedVersion())
    }

    @Test
    fun pagedChangesContinueFromTheLastEntryAndPersistThePageVersionWhenItIsHigher() {
        val first = string("a")
        val second = string("b")
        server.seed("unregistered", json("x"), 5)
        start()
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 10,
            entries = listOf(settingEntry("a", json("p1"), 6)),
            hasMore = true
        )
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 10,
            entries = listOf(settingEntry("b", json("p2"), 8))
        )

        announce(version = 10)

        assertEquals(listOf(5L, 6L), server.changeRequests)
        assertEquals("p1", first.flow.value)
        assertEquals("p2", second.flow.value)
        assertEquals(10L, persistedVersion())
    }

    @Test
    fun pagedChangesPersistTheLastEntryVersionWhenThePageVersionIsLower() {
        string("a")
        string("b")
        server.seed("unregistered", json("x"), 5)
        start()
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 7,
            entries = listOf(settingEntry("a", json("p1"), 6)),
            hasMore = true
        )
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 7,
            entries = listOf(settingEntry("b", json("p2"), 8))
        )

        announce(version = 8)

        assertEquals(listOf(5L, 6L), server.changeRequests)
        assertEquals(8L, persistedVersion())
    }

    @Test
    fun fullResyncReadsTheSnapshotAndDoesNotApplyThePageEntries() {
        val setting = string("a")
        start()
        server.foreignWrite("a", json("snapshot"))
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 9,
            entries = listOf(settingEntry("a", json("page"), 9)),
            fullResync = true
        )

        announce()

        assertEquals(2, server.snapshotCalls)
        assertEquals("snapshot", setting.flow.value)
        assertEquals(1, setting.setterCalls)
    }

    @Test
    fun ownNotificationWithTheNextVersionAdvancesTheVersionWithoutPulling() {
        string("a")
        start()
        assertEquals(0L, persistedVersion())

        announce(version = 1, device = DEVICE)
        assertEquals(1L, persistedVersion())

        announce(version = 5, device = DEVICE)
        assertEquals(1L, persistedVersion())
        assertTrue(server.changeRequests.isEmpty())
    }

    @Test
    fun deviceScopedNotificationDoesNothing() {
        string("a")
        start()
        val callsBefore = server.log.size

        announce(version = 6, scope = ClientSettingScope.DEVICE)

        assertEquals(callsBefore, server.log.size)
        assertEquals(0L, persistedVersion())
    }

    @Test
    fun pulledMetaThatTheStoredKeyFailsForgetsTheKeyAndAsksForThePassphrase() {
        val (meta, key) = fastMeta("first")
        seedMeta(meta)
        cipher.remember(key)
        Config.setIsSecretsSyncEnabled(true)
        secret(SECRET_KEY)
        start()
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)

        server.foreignWrite(SecretsCipher.META_KEY, AppJson.encodeToString(fastMeta("second").first))
        announce()

        assertNull(cipher.key.value)
        assertFalse(settings.hasKey(SettingKey.SecretsSyncEncKey.name))
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
    }

    @Test
    fun sessionStillReconcilesWhenRegisteringTheDeviceFails() {
        val setting = string("a")
        server.seed("a", json("server"), 1)
        server.registerFailure = IllegalStateException("register")

        start()

        assertEquals(1, server.registrations.size)
        assertEquals(1, server.snapshotCalls)
        assertEquals("server", setting.flow.value)
    }

    private fun inactiveGateResetsTheSession(deactivate: () -> Unit) {
        val other = string("b")
        Config.setIsSecretsSyncEnabled(true)
        startWithConflict()
        other.flow.value = "b1"
        service.refreshDevices()
        scheduler.runCurrent()
        assertNotNull(service.pendingConflict.value)
        assertTrue(service.devices.value.isNotEmpty())
        assertEquals(SecretsLockState.NEEDS_SETUP, service.secretsLockState.value)
        assertEquals(1, service.status.value.pendingKeys)

        deactivate()
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertTrue(service.devices.value.isEmpty())
        assertEquals(SecretsLockState.DISABLED, service.secretsLockState.value)
        assertEquals(0, service.status.value.pendingKeys)

        debounce()
        change(other, "b2")
        debounce()
        advance(1.seconds)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun loginRequiredClearsConflictDevicesLockAndPendingKeysAndStopsPushing() =
        inactiveGateResetsTheSession { connection.value = ConnectionState.LoginRequired }

    @Test
    fun unreachableServerClearsConflictDevicesLockAndPendingKeysAndStopsPushing() =
        inactiveGateResetsTheSession { reachable.value = false }

    @Test
    fun anotherPersistedUserClearsTheKnownStateBeforeReconciling() {
        val setting = string("a")
        persist(9, mapOf("a" to SettingsSyncKnown(json("v1"), 5)), user = "someone-else")
        server.seed("a", json("v2"), 7)
        setting.flow.value = "v3"

        start()

        assertEquals(1, repository.clearCalls)
        assertNull(service.pendingConflict.value)
        assertEquals("v2", setting.flow.value)
        assertEquals(mapOf("a" to SettingsSyncKnown(json("v2"), 7)), repository.entries)
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun samePersistedUserLoadsTheKnownStateAndUploadsNothingAgain() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("v1"), 5)))
        server.seed("a", json("v1"), 5)
        setting.flow.value = "v1"

        start()

        assertEquals(0, repository.clearCalls)
        assertEquals(1, repository.getAllCalls)
        assertTrue(server.writes.isEmpty())
        assertEquals(0, setting.setterCalls)
    }

    @Test
    fun firstEnableWithACustomisedValueThatDiffersFromTheServerRaisesTheEnableChoiceAndTouchesNothing() {
        val (both, serverOnly, localOnly) = startFirstEnableWithDifference()

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf("a"), choice.keys)
        assertEquals("Laptop", choice.deviceName)
        assertNull(service.pendingConflict.value)
        assertEquals("mine", both.flow.value)
        assertEquals("", serverOnly.flow.value)
        assertEquals(0, both.setterCalls + serverOnly.setterCalls)
        assertTrue(server.writes.isEmpty())
        assertTrue(repository.entries.isEmpty())
        assertNull(settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))

        change(localOnly, "c-later")
        debounce()
        service.syncNow()
        scheduler.runCurrent()
        advance(1.seconds)

        assertTrue(server.writes.isEmpty())
        assertTrue(server.changeRequests.isEmpty())
        assertEquals(1, server.snapshotCalls)
        assertNotNull(service.pendingEnableChoice.value)
    }

    @Test
    fun keepingThisDeviceOnFirstEnablePushesTheCustomisedValuesAndAdoptsTheServerForUntouchedKeys() {
        val (both, serverOnly, localOnly) = startFirstEnableWithDifference()

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertNull(service.pendingConflict.value)
        assertEquals(2, server.snapshotCalls)
        assertEquals(
            listOf(
                WriteCall(
                    listOf(ClientSettingWrite("a", json("mine"), 1L), ClientSettingWrite("c", json("c-mine"), 0L)),
                    ClientSettingScope.SYNCED,
                    DEVICE,
                    false
                )
            ),
            server.writes
        )
        assertEquals(json("mine"), server.stored["a"]?.value)
        assertEquals("mine", both.flow.value)
        assertEquals("b-server", serverOnly.flow.value)
        assertEquals("c-mine", localOnly.flow.value)
        assertEquals(SettingsSyncKnown(json("mine"), 3), repository.entries["a"])
        assertEquals(SettingsSyncKnown(json("b-server"), 2), repository.entries["b"])
        assertEquals(SettingsSyncKnown(json("c-mine"), 3), repository.entries["c"])
        assertEquals(3L, persistedVersion())
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))

        debounce()
        advance(1.seconds)
        assertEquals(1, server.writes.size)
    }

    @Test
    fun takingTheServerOnFirstEnableAppliesItsValuesAndPushesOnlyTheKeysItLacks() {
        val (both, serverOnly, localOnly) = startFirstEnableWithDifference()

        service.resolveEnableChoice(keepLocal = false)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertNull(service.pendingConflict.value)
        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("c", json("c-mine"), 0L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
        assertEquals(json("theirs"), server.stored["a"]?.value)
        assertEquals("theirs", both.flow.value)
        assertEquals("b-server", serverOnly.flow.value)
        assertEquals("c-mine", localOnly.flow.value)
        assertEquals(SettingsSyncKnown(json("theirs"), 1), repository.entries["a"])
        assertEquals(SettingsSyncKnown(json("b-server"), 2), repository.entries["b"])
        assertEquals(SettingsSyncKnown(json("c-mine"), 3), repository.entries["c"])
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
    }

    @Test
    fun firstEnableWithoutACustomisedValueThatDiffersFromTheServerStartsWithoutAPrompt() {
        val same = string("a")
        val serverOnly = string("b")
        val localOnly = string("c")
        server.seed("a", json("same"), 1)
        server.seed("b", json("b-server"), 2)
        same.flow.value = "same"
        localOnly.flow.value = "c-mine"

        start()

        assertNull(service.pendingEnableChoice.value)
        assertEquals(1, server.snapshotCalls)
        assertEquals("same", same.flow.value)
        assertEquals("b-server", serverOnly.flow.value)
        assertEquals(listOf(listOf(ClientSettingWrite("c", json("c-mine"), 0L))), server.writes.map { it.entries })
        assertEquals(SettingsSyncKnown(json("same"), 1), repository.entries["a"])
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
    }

    @Test
    fun userSwitchWithACustomisedValueNeverPromptsAndNeverPushesIt() {
        val setting = string("a")
        persist(9, mapOf("a" to SettingsSyncKnown(json("v1"), 5)), user = "someone-else")
        server.seed("a", json("v2"), 7)
        setting.flow.value = "v3"

        start()

        assertNull(service.pendingEnableChoice.value)
        assertNull(service.pendingConflict.value)
        assertEquals(1, server.snapshotCalls)
        assertEquals("v2", setting.flow.value)
        assertEquals(json("v2"), server.stored["a"]?.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
    }

    @Test
    fun reEnablingAfterAnEarlierSyncMergesPerKeyWithoutAnEnableChoice() {
        val changedHere = string("a")
        val changedThere = string("b")
        persist(5, mapOf("a" to SettingsSyncKnown(json("a1"), 3), "b" to SettingsSyncKnown(json("b1"), 4)))
        server.seed("a", json("a1"), 3)
        server.seed("b", json("b2"), 7)
        changedHere.flow.value = "a2"
        changedThere.flow.value = "b1"

        start()

        assertNull(service.pendingEnableChoice.value)
        assertNull(service.pendingConflict.value)
        assertEquals(listOf(listOf(ClientSettingWrite("a", json("a2"), 3L))), server.writes.map { it.entries })
        assertEquals("a2", changedHere.flow.value)
        assertEquals("b2", changedThere.flow.value)
    }

    @Test
    fun remoteChangesDuringTheOpenEnableChoiceAreHandledAfterTheDecision() {
        val (both, serverOnly, _) = startFirstEnableWithDifference()
        server.foreignWrite("a", json("theirs-2"))
        server.foreignWrite("b", json("b-late"))
        announce()

        assertEquals("mine", both.flow.value)
        assertEquals("", serverOnly.flow.value)
        assertTrue(server.changeRequests.isEmpty())
        assertEquals(1, server.snapshotCalls)

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(2, server.snapshotCalls)
        assertTrue(server.changeRequests.isEmpty())
        assertEquals(
            listOf(listOf(ClientSettingWrite("a", json("mine"), 3L), ClientSettingWrite("c", json("c-mine"), 0L))),
            server.writes.map { it.entries }
        )
        assertEquals(json("mine"), server.stored["a"]?.value)
        assertEquals("mine", both.flow.value)
        assertEquals("b-late", serverOnly.flow.value)
        assertEquals(SettingsSyncKnown(json("b-late"), 4), repository.entries["b"])
        assertNull(service.pendingConflict.value)
    }

    private fun lockedSecretsStayUntouchedByTheEnableChoice(keepLocal: Boolean) {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        val cipherText = envelope(SECRET_KEY, "remote-token", key)
        val tokenEntry = server.seed(SECRET_KEY, cipherText, server.version + 1)
        val flagEntry = server.seed(FLAG_KEY, json("remote-flag"), server.version + 1)
        server.seed("a", json("theirs"), server.version + 1)
        val general = string("a")
        val token = secret(SECRET_KEY)
        val flag = string(FLAG_KEY, SyncGroup.SECRETS)
        Config.setIsSecretsSyncEnabled(true)
        general.flow.value = "mine"
        token.flow.value = "local-token"
        flag.flow.value = "local-flag"

        start()

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf("a"), choice.keys)

        service.resolveEnableChoice(keepLocal)
        scheduler.runCurrent()

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertEquals(if (keepLocal) "mine" else "theirs", general.flow.value)
        assertEquals("local-token", token.flow.value)
        assertEquals("local-flag", flag.flow.value)
        assertEquals(0, token.setterCalls + flag.setterCalls)
        assertEquals(
            if (keepLocal) listOf(listOf("a")) else emptyList<List<String>>(),
            server.writes.map { call -> call.entries.map { it.key } }
        )
        assertEquals(tokenEntry, server.stored[SECRET_KEY])
        assertEquals(flagEntry, server.stored[FLAG_KEY])
        assertEquals(SettingsSyncKnown(cipherText, tokenEntry.version), repository.entries[SECRET_KEY])
        assertEquals(SettingsSyncKnown(json("remote-flag"), flagEntry.version), repository.entries[FLAG_KEY])
    }

    @Test
    fun keepingThisDeviceLeavesLockedSecretsUntouched() = lockedSecretsStayUntouchedByTheEnableChoice(keepLocal = true)

    @Test
    fun takingTheServerLeavesLockedSecretsUntouched() = lockedSecretsStayUntouchedByTheEnableChoice(keepLocal = false)

    @Test
    fun unlockedSecretsTakePartInTheEnableChoice() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        val tokenEntry = server.seed(SECRET_KEY, envelope(SECRET_KEY, "remote-token", key), server.version + 1)
        cipher.remember(key)
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "local-token"

        start()

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertTrue(server.writes.isEmpty())

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        val write = server.writes.single().entries.single()
        assertEquals(SECRET_KEY, write.key)
        assertEquals(tokenEntry.version, write.baseVersion)
        val cipherText = assertNotNull(write.value)
        assertFalse(cipherText.contains("local-token"))
        assertEquals("local-token", cipher.decryptFromJson(SECRET_KEY, cipherText))
        assertEquals("local-token", token.flow.value)
        assertEquals(0, token.setterCalls)
    }

    @Test
    fun cancellingTheEnableChoiceTurnsSyncOffLeavesNothingBehindAndAsksAgainOnTheNextEnable() {
        val (both, serverOnly, _) = startFirstEnableWithDifference()

        service.cancelEnableChoice()
        scheduler.runCurrent()

        assertFalse(Config.isSettingsSyncEnabled.value)
        assertNull(service.pendingEnableChoice.value)
        assertEquals("mine", both.flow.value)
        assertEquals("", serverOnly.flow.value)
        assertTrue(server.writes.isEmpty())
        assertTrue(repository.entries.isEmpty())
        assertNull(settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
        assertEquals(0, service.status.value.pendingKeys)

        Config.setIsSettingsSyncEnabled(true)
        scheduler.runCurrent()

        assertEquals(2, server.snapshotCalls)
        assertEquals(listOf("a"), service.pendingEnableChoice.value?.keys)
        assertTrue(server.writes.isEmpty())
        assertEquals("mine", both.flow.value)
    }

    @Test
    fun aGateChangeWhileTheEnableChoiceIsOpenClearsThePromptAndAsksAgainWhenItReturns() {
        val (both, _, _) = startFirstEnableWithDifference()

        reachable.value = false
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertTrue(Config.isSettingsSyncEnabled.value)
        assertEquals("mine", both.flow.value)
        assertTrue(server.writes.isEmpty())
        assertTrue(repository.entries.isEmpty())
        assertNull(settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))

        reachable.value = true
        scheduler.runCurrent()

        assertEquals(2, server.snapshotCalls)
        assertEquals(listOf("a"), service.pendingEnableChoice.value?.keys)
        assertTrue(server.writes.isEmpty())
    }

    private fun startLockedWithADifferingLocalSecret(): Pair<LocalSetting<String>, DerivedKey> {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        server.seed(SECRET_KEY, envelope(SECRET_KEY, "remote-token", key), server.version + 1)
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "local-token"
        start()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNull(service.pendingEnableChoice.value)
        return token to key
    }

    @Test
    fun firstUnlockWithADifferingLocalSecretAsksWhileGeneralSettingsKeepSyncingAndTheSecretWaits() {
        val general = string("a")
        val other = string("b")
        val (token, _) = startLockedWithADifferingLocalSecret()

        assertTrue(setPassphrase("passphrase"))

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertTrue(choice.onUnlock)
        assertNull(choice.deviceName)
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNull(cipher.key.value)
        assertEquals("local-token", token.flow.value)
        assertTrue(server.writes.isEmpty())

        change(general, "g1")
        debounce()
        server.foreignWrite("b", json("remote-b"))
        announce()
        advance(1.seconds)

        assertEquals(listOf(listOf("a")), server.writes.map { call -> call.entries.map { it.key } })
        assertEquals("remote-b", other.flow.value)
        assertEquals("local-token", token.flow.value)
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNotNull(service.pendingEnableChoice.value)

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals(2, server.writes.size)
        val write = server.writes.last()
        assertFalse(write.force)
        assertEquals(listOf(SECRET_KEY), write.entries.map { it.key })
        val secretWrite = write.entries.single()
        assertEquals(2L, secretWrite.baseVersion)
        assertEquals("local-token", cipher.decryptFromJson(SECRET_KEY, assertNotNull(secretWrite.value)))
        assertEquals("local-token", token.flow.value)
        assertEquals(0, token.setterCalls)
        assertEquals("remote-b", other.flow.value)
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun usingTheSyncedAccountsOnFirstUnlockAppliesTheServerSecretWithoutAWrite() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)

        service.resolveEnableChoice(keepLocal = false)
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingEnableChoice.value)
        assertEquals("remote-token", token.flow.value)
        assertEquals(1, token.setterCalls)
        assertTrue(server.writes.isEmpty())
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
    }

    @Test
    fun cancellingTheUnlockChoiceLocksTheSecretsAgainAndAsksAgainOnTheNextUnlock() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)

        service.cancelEnableChoice()
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertNull(cipher.key.value)
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertTrue(Config.isSettingsSyncEnabled.value)
        assertEquals("local-token", token.flow.value)
        assertTrue(server.writes.isEmpty())

        assertTrue(setPassphrase("passphrase"))

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertTrue(choice.onUnlock)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun unlockWithAMatchingLocalSecretAsksNothing() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        server.seed(SECRET_KEY, envelope(SECRET_KEY, "same-token", key), server.version + 1)
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "same-token"
        start()

        assertTrue(setPassphrase("passphrase"))
        debounce()

        assertNull(service.pendingEnableChoice.value)
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("same-token", token.flow.value)
        assertEquals(0, token.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun anAccountSwitchDoesNotAskOnUnlockAndTheServerSecretWins() {
        persist(9, emptyMap(), user = "someone-else")
        val (token, _) = startLockedWithADifferingLocalSecret()

        assertTrue(setPassphrase("passphrase"))
        debounce()

        assertNull(service.pendingEnableChoice.value)
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("remote-token", token.flow.value)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun keepingTheLocalValuesWhileTheSecretsAreLockedWritesNoSecret() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        cipher.remember(key)
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "t-local"
        start()
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals(1, server.writes.size)
        val remote = server.foreignWrite(SECRET_KEY, envelope(SECRET_KEY, "t-remote", key))
        change(token, "t-edit")
        debounce()
        assertEquals(listOf(SECRET_KEY), service.pendingConflict.value?.keys)
        assertEquals(2, server.writes.size)
        cipher.forget()
        scheduler.runCurrent()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)

        service.resolveConflict(keepLocal = true)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertEquals(2, server.writes.size)
        assertTrue(server.writes.none { it.force })
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("t-edit", token.flow.value)
    }

    @Test
    fun aKnownKeyMissingFromTheSnapshotResetsAnUnchangedLocalValueToDefaultWithoutWriting() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("x"), 3)))
        server.seed("unregistered", json("x"), 6)
        setting.flow.value = "x"

        start()
        debounce()
        advance(1.seconds)

        assertEquals("", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertNull(repository.entries["a"])
        assertTrue(server.writes.isEmpty())
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun aKnownKeyMissingFromTheSnapshotWhileTheLocalValueChangedRaisesAConflictAndTakingTheDeletionResetsIt() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("x"), 3)))
        setting.flow.value = "y"

        start()
        debounce()
        advance(1.seconds)

        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertTrue(conflict.entries.single().deleted)
        assertNull(conflict.deviceName)
        assertEquals("y", setting.flow.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(SettingsSyncKnown(json("x"), 3), repository.entries["a"])

        service.resolveConflict(keepLocal = false)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertEquals("", setting.flow.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(SettingsSyncKnown(null, 0L), repository.entries["a"])

        change(setting, "z")
        debounce()

        assertEquals(listOf(listOf(ClientSettingWrite("a", json("z"), 0L))), server.writes.map { it.entries })
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun keepingTheLocalValueOverADeletionMadeElsewhereForcesItBackOntoTheServer() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("x"), 3)))
        setting.flow.value = "y"
        start()
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)

        service.resolveConflict(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("y"), 0L)), ClientSettingScope.SYNCED, DEVICE, true)),
            server.writes
        )
        assertEquals(json("y"), server.stored["a"]?.value)
        assertEquals("y", setting.flow.value)
        assertEquals(SettingsSyncKnown(json("y"), 1), repository.entries["a"])
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun aFailingFirstWriteKeepsTheSessionAliveAndTheWriteIsSentAgainAfterTheBackoff() {
        val setting = string("a")
        val other = string("b")
        setting.flow.value = "mine"
        server.writeFailure = IOException("write")

        start()

        assertEquals(2, server.writes.size)
        assertTrue(server.stored.isEmpty())
        server.writeFailure = null

        advance(999.milliseconds)
        assertEquals(2, server.writes.size)
        advance(1.milliseconds)

        assertEquals(3, server.writes.size)
        assertEquals(WriteCall(listOf(ClientSettingWrite("a", json("mine"), 0L)), ClientSettingScope.SYNCED, DEVICE, false), server.writes.last())
        assertEquals(SettingsSyncKnown(json("mine"), 1), repository.entries["a"])
        assertEquals(0, service.status.value.pendingKeys)

        server.foreignWrite("b", json("remote"))
        announce()

        assertEquals("remote", other.flow.value)
    }

    @Test
    fun aFailedKnownStateWriteIsWrittenAgainWithTheNextPersist() {
        val first = string("a")
        val second = string("b")
        start()
        repository.putAllFailure = IllegalStateException("db")

        change(first, "1")
        debounce()
        assertEquals(1, server.writes.size)
        assertNull(repository.entries["a"])

        change(second, "2")
        debounce()

        assertEquals(SettingsSyncKnown(json("1"), 1), repository.entries["a"])
        assertEquals(SettingsSyncKnown(json("2"), 2), repository.entries["b"])
    }

    @Test
    fun anUnansweredConflictKeepsTheKnownStateAndAFullResyncRaisesItAgainWithoutWriting() {
        val (setting, serverEntry) = startWithConflict()
        assertEquals(SettingsSyncKnown(json("v1"), 5), repository.entries["a"])
        server.scriptedPages += ClientSettingsChanges(
            scope = ClientSettingScope.SYNCED,
            version = 7,
            entries = emptyList(),
            fullResync = true
        )

        announce()
        debounce()
        advance(1.seconds)

        assertEquals(2, server.snapshotCalls)
        assertTrue(server.writes.isEmpty())
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(serverEntry), conflict.entries)
        assertEquals("v3", setting.flow.value)
        assertEquals(SettingsSyncKnown(json("v1"), 5), repository.entries["a"])
    }

    @Test
    fun anUnansweredConflictIsRaisedAgainAfterARestartInsteadOfWritingTheLocalValue() {
        val (setting, serverEntry) = startWithConflict()
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        assertNull(service.pendingConflict.value)
        restartService()

        start()
        debounce()
        advance(1.seconds)

        assertTrue(server.writes.isEmpty())
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(serverEntry), conflict.entries)
        assertEquals("v3", setting.flow.value)
        assertEquals(json("v2"), server.stored["a"]?.value)
    }

    @Test
    fun aPulledChangeToAConflictedKeyRefreshesTheConflictWithoutTouchingTheLocalValue() {
        val setting = string("a")
        start()
        server.foreignWrite("a", json("remote"))
        change(setting, "mine")
        debounce()
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)
        assertNull(repository.entries["a"])
        val newer = server.foreignWrite("a", json("remote-2"))

        announce()

        assertEquals("mine", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals(listOf(newer), service.pendingConflict.value?.entries)
        assertNull(repository.entries["a"])
        assertEquals(2L, persistedVersion())

        service.resolveConflict(keepLocal = false)
        scheduler.runCurrent()

        assertEquals("remote-2", setting.flow.value)
        assertEquals(SettingsSyncKnown(json("remote-2"), 2), repository.entries["a"])
        assertNull(service.pendingConflict.value)
    }

    private fun restartService() {
        every { rpcServiceManager.connectionState } returns MutableStateFlow(ConnectionState.Authenticated)
        rebuildService()
    }

    private fun rebuildService(dispatchers: SynaraDispatchers = TestDispatchers(dispatcher)) {
        service = SettingsSyncService(
            clientSettingsService = server,
            identity = identity,
            settings = settings,
            repository = repository,
            registry = registry,
            cipher = cipher,
            rpcServiceManager = rpcServiceManager,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers
        )
    }

    private fun slowDerivationDispatchers(): SynaraDispatchers = object : SynaraDispatchers by TestDispatchers(dispatcher) {
        override val default: CoroutineDispatcher = StandardTestDispatcher(scheduler)
    }

    private fun gateFlipDuringTheKeyDerivationRefusesTheUnlock(deactivate: () -> Unit) {
        rebuildService(slowDerivationDispatchers())
        val (token, _) = startLockedWithADifferingLocalSecret()
        val remote = assertNotNull(server.stored[SECRET_KEY])

        val result = CoroutineScope(dispatcher).async { service.setPassphrase("passphrase") }
        deactivate()
        scheduler.runCurrent()

        verify(exactly = 1) { cipher.deriveKey("passphrase", any()) }
        assertFalse(result.getCompleted())
        assertNull(cipher.key.value)
        assertNull(service.pendingEnableChoice.value)
        assertEquals("local-token", token.flow.value)

        connection.value = ConnectionState.Authenticated
        reachable.value = true
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("local-token", token.flow.value)

        assertTrue(setPassphrase("passphrase"))

        assertEquals(listOf(SECRET_KEY), service.pendingEnableChoice.value?.keys)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aReachabilityChangeDuringTheKeyDerivationRefusesTheUnlockAndRemembersNoKey() =
        gateFlipDuringTheKeyDerivationRefusesTheUnlock { reachable.value = false }

    @Test
    fun aLogoutDuringTheKeyDerivationRefusesTheUnlockAndRemembersNoKey() =
        gateFlipDuringTheKeyDerivationRefusesTheUnlock { connection.value = ConnectionState.LoginRequired }

    @Test
    fun theUnlockQuestionDoesNotOutliveTheSessionWhenItWasQueuedBehindAStalledPush() {
        val general = string("a")
        val (token, _) = startLockedWithADifferingLocalSecret()
        val remote = assertNotNull(server.stored[SECRET_KEY])
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        server.writeGates += firstGate
        server.writeGates += secondGate
        change(general, "g1")
        service.syncNow()
        scheduler.runCurrent()
        val result = CoroutineScope(dispatcher).async { service.setPassphrase("passphrase") }
        scheduler.runCurrent()
        change(general, "g2")
        service.syncNow()
        scheduler.runCurrent()
        firstGate.complete(Unit)
        scheduler.runCurrent()
        assertTrue(result.getCompleted())

        reachable.value = false
        scheduler.runCurrent()
        secondGate.complete(Unit)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertNull(cipher.key.value)

        reachable.value = true
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertTrue(server.writes.none { call -> call.entries.any { it.key == SECRET_KEY } })
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("local-token", token.flow.value)
    }

    @Test
    fun secretsSyncTurnedOffAndOnWithAChangedLocalSecretAsksInsteadOfOverwritingIt() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        val remote = server.seed(SECRET_KEY, envelope(SECRET_KEY, "remote-token", key), server.version + 1)
        cipher.remember(key)
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        start()
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("remote-token", token.flow.value)

        Config.setIsSecretsSyncEnabled(false)
        scheduler.runCurrent()
        assertEquals(SecretsLockState.DISABLED, service.secretsLockState.value)
        change(token, "edited")
        debounce()
        assertTrue(server.writes.isEmpty())

        Config.setIsSecretsSyncEnabled(true)
        scheduler.runCurrent()

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertTrue(choice.onUnlock)
        assertNotEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("edited", token.flow.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(remote, server.stored[SECRET_KEY])

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        val write = server.writes.single().entries.single()
        assertEquals(SECRET_KEY, write.key)
        assertEquals(remote.version, write.baseVersion)
        assertEquals("edited", cipher.decryptFromJson(SECRET_KEY, assertNotNull(write.value)))
        assertEquals("edited", token.flow.value)
    }

    @Test
    fun aFailingFirstKeepLocalWriteIsRetriedAgainstTheServerVersionWithoutAConflict() {
        val (both, serverOnly, localOnly) = startFirstEnableWithDifference()
        server.writeFailure = IOException("write")

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(SettingsSyncKnown(json("theirs"), 1), repository.entries["a"])
        assertNull(service.pendingConflict.value)
        server.writeFailure = null

        advance(1.seconds)

        assertNull(service.pendingConflict.value)
        assertNull(service.pendingEnableChoice.value)
        assertEquals(
            listOf(ClientSettingWrite("a", json("mine"), 1L), ClientSettingWrite("c", json("c-mine"), 0L)),
            server.writes.last().entries
        )
        assertEquals(json("mine"), server.stored["a"]?.value)
        assertEquals("mine", both.flow.value)
        assertEquals("b-server", serverOnly.flow.value)
        assertEquals("c-mine", localOnly.flow.value)
        assertEquals(SettingsSyncKnown(json("mine"), 3), repository.entries["a"])
        assertEquals(0, service.status.value.pendingKeys)
    }

    @Test
    fun aConflictAnswerArrivingAfterAResetIsIgnoredAndThePersistedUserSurvives() {
        val other = string("b")
        val (setting, serverEntry) = startWithConflict()
        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
        val gate = CompletableDeferred<Unit>()
        server.writeGates += gate
        other.flow.value = "b1"
        service.syncNow()
        scheduler.runCurrent()
        assertEquals(1, server.writes.size)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)
        service.resolveConflict(keepLocal = true)
        scheduler.runCurrent()
        assertEquals(1, server.writes.size)
        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(USER, settings.getStringOrNull(SettingKey.SettingsSyncUserId.name))
        assertNull(service.pendingConflict.value)
        assertTrue(server.writes.none { it.force })
        assertEquals(json("v2"), server.stored["a"]?.value)
        assertEquals("v3", setting.flow.value)

        connection.value = ConnectionState.Authenticated
        scheduler.runCurrent()

        assertEquals(0, repository.clearCalls)
        assertNull(service.pendingEnableChoice.value)
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf(serverEntry), conflict.entries)
    }

    @Test
    fun aStaleRememberedKeyDoesNotHideTheDifferenceFromTheCandidateKey() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        cipher.remember(fastMeta("other").second)
        scheduler.runCurrent()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)

        assertTrue(setPassphrase("passphrase"))

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertEquals("local-token", token.flow.value)
        assertEquals(0, token.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aReachabilityChangeWhileTheUnlockChoiceIsOpenLeavesTheSecretsLockedAndTheNextSessionUploadsNothing() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        val remote = assertNotNull(server.stored[SECRET_KEY])
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)

        reachable.value = false
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertNull(cipher.key.value)

        reachable.value = true
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("local-token", token.flow.value)

        assertTrue(setPassphrase("passphrase"))

        assertEquals(listOf(SECRET_KEY), service.pendingEnableChoice.value?.keys)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aRestartWhileTheUnlockChoiceIsOpenLeavesTheSecretsLockedAndUploadsNothing() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        val remote = assertNotNull(server.stored[SECRET_KEY])
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        restartService()

        start()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNull(cipher.key.value)
        assertNull(service.pendingEnableChoice.value)
        assertTrue(server.writes.isEmpty())
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("local-token", token.flow.value)
    }

    @Test
    fun cancellingTheUnlockChoiceAfterAChangeAndAPullDuringItWritesAndAppliesNoSecret() {
        val general = string("a")
        val other = string("b")
        val (token, _) = startLockedWithADifferingLocalSecret()
        val remote = assertNotNull(server.stored[SECRET_KEY])
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)
        change(general, "g1")
        debounce()
        server.foreignWrite("b", json("remote-b"))
        announce()
        assertEquals(listOf(listOf("a")), server.writes.map { call -> call.entries.map { it.key } })

        service.cancelEnableChoice()
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNull(cipher.key.value)
        assertEquals(listOf(listOf(ClientSettingWrite("a", json("g1"), 0L))), server.writes.map { it.entries })
        assertEquals("remote-b", other.flow.value)
        assertEquals(remote, server.stored[SECRET_KEY])
        assertEquals("local-token", token.flow.value)
        assertEquals(0, token.setterCalls)
    }

    @Test
    fun aSecretDeletedElsewhereWhileLockedIsKeptAsATombstoneAndTheUnlockAsksBeforeWipingTheLocalOne() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        persist(1, mapOf(SECRET_KEY to SettingsSyncKnown(envelope(SECRET_KEY, "old-token", key), 1)))
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "local-token"

        start()

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertEquals(SettingsSyncKnown(null, 0L), repository.entries[SECRET_KEY])
        assertTrue(server.writes.isEmpty())

        assertTrue(setPassphrase("passphrase"))

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertTrue(choice.onUnlock)
        assertTrue(server.writes.isEmpty())
        assertEquals("local-token", token.flow.value)

        service.resolveEnableChoice(keepLocal = false)
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("", token.flow.value)
        assertEquals(1, token.setterCalls)
        assertTrue(server.writes.isEmpty())
        assertNull(server.stored[SECRET_KEY])
    }

    @Test
    fun keepingTheLocalSecretOverADeletionMadeElsewhereUploadsItWithBaseVersionZero() {
        val (meta, key) = fastMeta("passphrase")
        seedMeta(meta)
        persist(1, mapOf(SECRET_KEY to SettingsSyncKnown(envelope(SECRET_KEY, "old-token", key), 1)))
        Config.setIsSecretsSyncEnabled(true)
        val token = secret(SECRET_KEY)
        token.flow.value = "local-token"
        start()
        assertTrue(setPassphrase("passphrase"))
        assertNotNull(service.pendingEnableChoice.value)

        service.resolveEnableChoice(keepLocal = true)
        scheduler.runCurrent()

        val write = server.writes.single().entries.single()
        assertEquals(SECRET_KEY, write.key)
        assertEquals(0L, write.baseVersion)
        assertEquals("local-token", cipher.decryptFromJson(SECRET_KEY, assertNotNull(write.value)))
        assertEquals("local-token", token.flow.value)
        assertEquals(0, token.setterCalls)
    }

    @Test
    fun aSecretTombstonePulledWhileLockedMakesTheUnlockAskBeforeWipingTheLocalOne() {
        val (token, _) = startLockedWithADifferingLocalSecret()
        server.foreignWrite(SECRET_KEY, null)

        announce()

        assertEquals(SettingsSyncKnown(null, 3L), repository.entries[SECRET_KEY])
        assertEquals("local-token", token.flow.value)

        assertTrue(setPassphrase("passphrase"))

        val choice = assertNotNull(service.pendingEnableChoice.value)
        assertEquals(listOf(SECRET_KEY), choice.keys)
        assertEquals("local-token", token.flow.value)
        assertTrue(server.writes.isEmpty())

        service.resolveEnableChoice(keepLocal = false)
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertEquals("", token.flow.value)
        assertEquals(1, token.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aConflictOnAKeyWithoutAKnownBaselineIsRaisedAgainAfterARestartInsteadOfAdoptingTheServer() {
        val setting = string("a")
        start()
        val remote = server.foreignWrite("a", json("remote"))
        change(setting, "mine")
        debounce()
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)
        assertEquals(1, server.writes.size)
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        restartService()

        start()
        debounce()
        advance(1.seconds)

        assertEquals(1, server.writes.size)
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(remote), conflict.entries)
        assertEquals("mine", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals(json("remote"), server.stored["a"]?.value)
    }

    @Test
    fun aKnownKeyMissingFromTheSnapshotWithTheLocalValueBackAtTheDefaultIsForgottenWithoutAConflict() {
        val setting = nullableString("n")
        persist(5, mapOf("n" to SettingsSyncKnown(json("de"), 3)))

        start()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingConflict.value)
        assertNull(repository.entries["n"])
        assertNull(setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aRejectedWriteIsNotRetriedWithBackoffAndDoesNotBlockALaterChangeToAnotherKey() {
        val rejected = string("a")
        val accepted = string("b")
        start()
        server.writeHandler = { call ->
            if (call.entries.any { it.key == "a" }) throw IllegalStateException("rejected")
            server.version++
            ClientSettingsWriteResult.Ok(
                server.version,
                call.entries.map { write ->
                    settingEntry(write.key, write.value, server.version, DEVICE).also { server.stored[it.key] = it }
                }
            )
        }

        change(rejected, "bad")
        debounce()
        assertEquals(1, server.writes.size)
        advance(30.seconds)
        assertEquals(1, server.writes.size)

        change(accepted, "ok")
        debounce()

        assertEquals(
            listOf(listOf("a"), listOf("a", "b"), listOf("a"), listOf("b")),
            server.writes.map { call -> call.entries.map { it.key } }
        )
        assertEquals(json("ok"), server.stored["b"]?.value)
        assertEquals(SettingsSyncKnown(json("ok"), 1), repository.entries["b"])
        assertNull(server.stored["a"])
        assertEquals(1, service.status.value.pendingKeys)
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun startingTwiceReadsOneSnapshot() {
        service.start()
        service.start()
        scheduler.runCurrent()

        assertEquals(1, server.snapshotCalls)
        assertEquals(1, server.registrations.size)
    }

    @Test
    fun deviceNameChangeDuringASessionRegistersTheDeviceAgainWithTheNewName() {
        start()
        assertEquals(listOf(Registration(DEVICE, "Desk", PLATFORM)), server.registrations)

        currentDeviceName = "Renamed"
        Config.setQueueSyncDeviceName("Renamed")
        scheduler.runCurrent()

        assertEquals(
            listOf(Registration(DEVICE, "Desk", PLATFORM), Registration(DEVICE, "Renamed", PLATFORM)),
            server.registrations
        )
    }

    @Test
    fun keepingTheLocalValuesForcesAWriteOfExactlyTheConflictedKeysAndClearsTheConflict() {
        val other = string("b")
        startWithConflict()
        other.flow.value = "b1"

        service.resolveConflict(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("v3"), 7L)), ClientSettingScope.SYNCED, DEVICE, true)),
            server.writes
        )
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun takingTheServerValuesAppliesThemLocallyWithoutAWriteAndClearsTheConflict() {
        val (setting, _) = startWithConflict()

        service.resolveConflict(keepLocal = false)
        scheduler.runCurrent()

        assertEquals("v2", setting.flow.value)
        assertTrue(server.writes.isEmpty())
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun conflictStaysPendingWhenTheForcedWriteFails() {
        val (setting, _) = startWithConflict()
        server.writeFailure = IllegalStateException("write")

        service.resolveConflict(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(listOf(true), server.writes.map { it.force })
        assertEquals(listOf("a"), service.pendingConflict.value?.keys)
        assertEquals("v3", setting.flow.value)
    }

    @Test
    fun settingAPassphraseWithoutRemoteMetaWritesTheMetaUnlocksAndPushesEncryptedSecrets() {
        val setting = secret(SECRET_KEY)
        setting.flow.value = "token"
        Config.setIsSecretsSyncEnabled(true)
        every { cipher.createMeta(any()) } answers { fastMeta(firstArg()) }
        start()
        assertEquals(SecretsLockState.NEEDS_SETUP, service.secretsLockState.value)
        assertTrue(server.writes.isEmpty())

        assertTrue(setPassphrase("passphrase"))

        verify(exactly = 1) { cipher.createMeta("passphrase") }
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals(2, server.writes.size)
        val metaWrite = server.writes[0].entries.single()
        assertEquals(SecretsCipher.META_KEY, metaWrite.key)
        assertEquals(0L, metaWrite.baseVersion)
        val meta = assertNotNull(cipher.parseMeta(assertNotNull(metaWrite.value)))
        assertTrue(cipher.verify(meta, cipher.deriveKey("passphrase", meta)))
        assertTrue(cipher.verify(meta, assertNotNull(cipher.key.value)))
        assertEquals(metaWrite.value, server.stored[SecretsCipher.META_KEY]?.value)
        val secretWrite = server.writes[1].entries.single()
        assertEquals(SECRET_KEY, secretWrite.key)
        assertEquals(0L, secretWrite.baseVersion)
        val cipherText = assertNotNull(secretWrite.value)
        assertFalse(cipherText.contains("token"))
        assertEquals("token", cipher.decryptFromJson(SECRET_KEY, cipherText))
    }

    @Test
    fun setupAnsweredWithAnotherDevicesMetaUnlocksWithTheRightPassphrase() {
        secret(SECRET_KEY)
        Config.setIsSecretsSyncEnabled(true)
        every { cipher.createMeta(any()) } answers { fastMeta(firstArg()) }
        start()
        assertEquals(SecretsLockState.NEEDS_SETUP, service.secretsLockState.value)
        val (serverMeta, _) = fastMeta("right")
        val seeded = seedMeta(serverMeta)

        assertTrue(setPassphrase("right"))

        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertTrue(cipher.verify(serverMeta, assertNotNull(cipher.key.value)))
        assertEquals(seeded, server.stored[SecretsCipher.META_KEY])
        assertEquals(1, server.writes.size)
    }

    @Test
    fun setupAnsweredWithAnotherDevicesMetaStaysLockedWithTheWrongPassphrase() {
        secret(SECRET_KEY)
        Config.setIsSecretsSyncEnabled(true)
        every { cipher.createMeta(any()) } answers { fastMeta(firstArg()) }
        start()
        assertEquals(SecretsLockState.NEEDS_SETUP, service.secretsLockState.value)
        val seeded = seedMeta(fastMeta("right").first)

        assertFalse(setPassphrase("wrong"))

        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertNull(cipher.key.value)
        assertEquals(seeded, server.stored[SecretsCipher.META_KEY])
        assertEquals(1, server.writes.size)
    }

    @Test
    fun enteringThePassphraseUnlocksAndDecryptsTheKnownSecretsIntoLocalSettings() {
        val (meta, key) = fastMeta("right")
        seedMeta(meta)
        server.seed(SECRET_KEY, envelope(SECRET_KEY, "remote-token", key), server.version + 1)
        val setting = secret(SECRET_KEY)
        Config.setIsSecretsSyncEnabled(true)
        start()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)

        assertFalse(setPassphrase("wrong"))
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        assertEquals("", setting.flow.value)

        assertTrue(setPassphrase("right"))
        assertEquals(SecretsLockState.UNLOCKED, service.secretsLockState.value)
        assertEquals("remote-token", setting.flow.value)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun passphraseIsRejectedWithoutASession() {
        Config.setIsSecretsSyncEnabled(true)

        assertFalse(setPassphrase("passphrase"))
        assertTrue(server.log.isEmpty())
    }

    @Test
    fun passphraseIsRejectedWhileSecretsSyncIsDisabled() {
        secret(SECRET_KEY)
        start()
        assertEquals(SecretsLockState.DISABLED, service.secretsLockState.value)

        assertFalse(setPassphrase("passphrase"))
        assertTrue(server.writes.isEmpty())
        assertNull(cipher.key.value)
    }

    @Test
    fun lockedSecretsNeverAppearInAWrite() {
        seedMeta(fastMeta("passphrase").first)
        Config.setIsSecretsSyncEnabled(true)
        val general = string("a")
        val flag = string(FLAG_KEY, SyncGroup.SECRETS)
        val token = secret(SECRET_KEY)
        general.flow.value = "g1"
        flag.flow.value = "f1"
        token.flow.value = "t1"

        start()
        assertEquals(SecretsLockState.NEEDS_PASSPHRASE, service.secretsLockState.value)
        change(flag, "f2")
        change(token, "t2")
        change(general, "g2")
        debounce()
        service.syncNow()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a"), listOf("a")), server.writes.map { call -> call.entries.map { it.key } })
    }

    @Test
    fun failingDeviceRefreshEndsNotBusyAndKeepsTheDevices() {
        val loaded = listOf(ClientDevice(OTHER_DEVICE, "Laptop", PLATFORM, 1L, 2L))
        server.devices = loaded
        service.refreshDevices()
        scheduler.runCurrent()
        assertEquals(loaded, service.devices.value)

        server.devicesFailure = IllegalStateException("devices")
        service.refreshDevices()
        scheduler.runCurrent()

        assertFalse(service.isBusy.value)
        assertFalse(service.status.value.isSyncing)
        assertEquals(loaded, service.devices.value)
    }

    @Test
    fun deletingADeviceDeletesItAndThenReloadsTheDevices() {
        val remaining = ClientDevice(DEVICE, "Desk", PLATFORM, 1L, 2L)
        server.devices = listOf(remaining, ClientDevice(OTHER_DEVICE, "Laptop", PLATFORM, 1L, 2L))

        service.deleteDevice(OTHER_DEVICE)
        scheduler.runCurrent()

        assertEquals(listOf(ServerCall.DELETE_DEVICE, ServerCall.GET_DEVICES), server.log)
        assertEquals(listOf(OTHER_DEVICE), server.deletedDevices)
        assertEquals(listOf(remaining), service.devices.value)
        assertFalse(service.isBusy.value)
    }

    @Test
    fun syncNowBeforeTheFirstSessionAndAfterTheSessionEndedCallsNothing() {
        val setting = string("a")
        val other = string("b")
        service.syncNow()
        scheduler.runCurrent()
        assertTrue(server.log.isEmpty())
        assertFalse(service.isBusy.value)

        start()
        assertEquals(1, server.snapshotCalls)
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        server.foreignWrite("a", json("remote"))
        change(other, "b1")
        val callsBefore = server.log.size

        service.syncNow()
        scheduler.runCurrent()
        advance(1.seconds)

        assertEquals(callsBefore, server.log.size)
        assertTrue(server.changeRequests.isEmpty())
        assertTrue(server.writes.isEmpty())
        assertEquals("", setting.flow.value)
        assertFalse(service.isBusy.value)
        assertFalse(service.status.value.isSyncing)
    }

    @Test
    fun syncNowWithALiveSessionPullsForeignChangesAndPushesAPendingChangeWithoutWaitingForTheDebounce() {
        val setting = string("a")
        val other = string("b")
        start()
        server.foreignWrite("a", json("remote"))
        change(other, "b1")
        assertTrue(server.changeRequests.isEmpty())
        assertTrue(server.writes.isEmpty())

        service.syncNow()
        scheduler.runCurrent()

        assertEquals(0L, scheduler.currentTime)
        assertEquals(listOf(0L), server.changeRequests)
        assertEquals("remote", setting.flow.value)
        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("b", json("b1"), 0L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
        assertEquals(2L, persistedVersion())
        assertFalse(service.isBusy.value)
        assertFalse(service.status.value.isSyncing)

        debounce()
        advance(1.seconds)

        assertEquals(1, server.writes.size)
        assertEquals(1, server.changeRequests.size)
    }

    @Test
    fun syncNowEndsNotBusyWhenReadingTheChangesFails() {
        string("a")
        start()
        server.changesFailure = IllegalStateException("changes")

        service.syncNow()
        scheduler.runCurrent()

        assertEquals(1, server.changeRequests.size)
        assertFalse(service.isBusy.value)
        assertFalse(service.status.value.isSyncing)
    }

    @Test
    fun aForeignChangeToAKeyWithALocalChangeStillInsideTheDebounceRaisesAConflictInsteadOfOverwritingIt() {
        val setting = string("a")
        server.seed("a", json("v1"), 1)
        start()
        assertEquals("v1", setting.flow.value)
        change(setting, "mine")
        val remote = server.foreignWrite("a", json("theirs"))

        announce()

        assertEquals("mine", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf("a"), conflict.keys)
        assertEquals(listOf(remote), conflict.entries)
        assertEquals(SettingsSyncKnown(json("v1"), 1), repository.entries["a"])
        assertEquals(2L, persistedVersion())

        debounce()
        advance(1.seconds)

        assertTrue(server.writes.isEmpty())
        assertEquals(0, service.status.value.pendingKeys)

        service.resolveConflict(keepLocal = false)
        scheduler.runCurrent()

        assertEquals("theirs", setting.flow.value)
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun aForeignChangeToAKeyWhoseWriteWasRejectedRaisesAConflictInsteadOfOverwritingIt() {
        val setting = string("a")
        start()
        server.writeHandler = { throw IllegalStateException("rejected") }
        change(setting, "mine")
        debounce()
        assertEquals(1, server.writes.size)
        assertEquals(1, service.status.value.pendingKeys)
        server.writeHandler = null
        val remote = server.foreignWrite("a", json("theirs"))

        announce()

        assertEquals("mine", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        val conflict = assertNotNull(service.pendingConflict.value)
        assertEquals(listOf(remote), conflict.entries)
        assertEquals(0, service.status.value.pendingKeys)
        assertNull(repository.entries["a"])
        assertEquals(1, server.writes.size)
    }

    @Test
    fun thisDevicesOwnEarlierWriteReturnedByAPullDoesNotUndoALaterLocalChange() {
        val setting = string("a")
        val other = string("b")
        start()
        server.foreignWrite("unregistered", json("x"))
        change(setting, "first")
        debounce()
        assertEquals(1, server.writes.size)
        assertEquals(0L, persistedVersion())
        change(setting, "second")
        server.foreignWrite("b", json("remote-b"))

        announce()

        assertEquals("second", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals("remote-b", other.flow.value)
        assertNull(service.pendingConflict.value)
        assertEquals(SettingsSyncKnown(json("first"), 2), repository.entries["a"])
        assertEquals(3L, persistedVersion())

        debounce()

        assertEquals(listOf(ClientSettingWrite("a", json("second"), 2L)), server.writes.last().entries)
        assertEquals(json("second"), server.stored["a"]?.value)
        assertEquals(SettingsSyncKnown(json("second"), 4), repository.entries["a"])
    }

    @Test
    fun aChangeMadeWhileTheObserveStreamWasDownIsPulledWhenTheLoopSubscribesAgain() {
        val setting = string("a")
        val end = CompletableDeferred<Unit>()
        server.streamEnds += end
        start()
        assertEquals(1, server.log.count { it == ServerCall.OBSERVE })
        assertTrue(server.changeRequests.isEmpty())

        end.complete(Unit)
        scheduler.runCurrent()
        server.foreignWrite("a", json("missed"))
        advance(4999.milliseconds)

        assertEquals("", setting.flow.value)
        assertTrue(server.changeRequests.isEmpty())
        assertEquals(1, server.log.count { it == ServerCall.OBSERVE })

        advance(1.milliseconds)

        assertEquals(listOf(0L), server.changeRequests)
        assertEquals("missed", setting.flow.value)
        assertEquals(1L, persistedVersion())
        assertEquals(2, server.log.count { it == ServerCall.OBSERVE })

        server.foreignWrite("a", json("live"))
        announce()

        assertEquals(listOf(0L, 1L), server.changeRequests)
        assertEquals("live", setting.flow.value)
    }

    private fun startWithKeysMissingFromTheSnapshot(count: Int): List<LocalSetting<String>> {
        val kept = (1..count).map { string("k$it") }
        persist(9, kept.associate { it.key to SettingsSyncKnown(json("v-${it.key}"), 5) })
        kept.forEach { it.flow.value = "v-${it.key}" }
        start()
        debounce()
        advance(1.seconds)
        return kept
    }

    @Test
    fun manyKnownKeysMissingFromTheSnapshotAskBeforeResettingAndStayFrozenUntilAnswered() {
        val other = string("b")
        server.seed("unregistered", json("x"), 9)
        val kept = startWithKeysMissingFromTheSnapshot(SettingsSyncService.RESET_PROMPT_THRESHOLD)

        val reset = assertNotNull(service.pendingReset.value)
        assertEquals(kept.map { it.key }, reset.keys)
        assertNull(service.pendingConflict.value)
        kept.forEach {
            assertEquals("v-${it.key}", it.flow.value)
            assertEquals(0, it.setterCalls)
            assertEquals(SettingsSyncKnown(json("v-${it.key}"), 5), repository.entries[it.key])
        }
        assertTrue(server.writes.isEmpty())
        assertEquals(0, service.status.value.pendingKeys)

        change(kept[0], "edited")
        change(other, "b1")
        debounce()
        advance(1.seconds)

        assertEquals(listOf(listOf(ClientSettingWrite("b", json("b1"), 0L))), server.writes.map { it.entries })
        assertEquals(0, service.status.value.pendingKeys)
        assertNotNull(service.pendingReset.value)
    }

    @Test
    fun acceptingTheResetAppliesTheDefaultsForgetsTheKeysAndWritesNothing() {
        val kept = startWithKeysMissingFromTheSnapshot(3)
        assertNotNull(service.pendingReset.value)

        service.resolveReset(keepLocal = false)
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingReset.value)
        kept.forEach {
            assertEquals("", it.flow.value)
            assertEquals(1, it.setterCalls)
            assertNull(repository.entries[it.key])
        }
        assertTrue(server.writes.isEmpty())
        assertEquals(0, service.status.value.pendingKeys)
    }

    @Test
    fun keepingThisDeviceOverTheResetUploadsItsValuesAgainWithoutForcing() {
        val kept = startWithKeysMissingFromTheSnapshot(3)
        assertNotNull(service.pendingReset.value)

        service.resolveReset(keepLocal = true)
        scheduler.runCurrent()

        assertNull(service.pendingReset.value)
        assertEquals(
            listOf(
                WriteCall(
                    kept.map { ClientSettingWrite(it.key, json("v-${it.key}"), 0L) },
                    ClientSettingScope.SYNCED,
                    DEVICE,
                    false
                )
            ),
            server.writes
        )
        kept.forEach {
            assertEquals("v-${it.key}", it.flow.value)
            assertEquals(0, it.setterCalls)
            assertEquals(json("v-${it.key}"), server.stored[it.key]?.value)
            assertEquals(SettingsSyncKnown(json("v-${it.key}"), 1), repository.entries[it.key])
        }
        assertEquals(1L, persistedVersion())

        debounce()
        advance(1.seconds)

        assertEquals(1, server.writes.size)
    }

    @Test
    fun fewerMissingKeysThanTheThresholdAreResetWithoutAskingAndAKeyAlreadyAtTheDefaultDoesNotCount() {
        val backAtDefault = string("d")
        persist(9, mapOf("d" to SettingsSyncKnown(json("old"), 5)))
        server.seed("unregistered", json("x"), 9)
        val kept = startWithKeysMissingFromTheSnapshot(SettingsSyncService.RESET_PROMPT_THRESHOLD - 1)

        assertNull(service.pendingReset.value)
        assertNull(service.pendingConflict.value)
        kept.forEach {
            assertEquals("", it.flow.value)
            assertEquals(1, it.setterCalls)
            assertNull(repository.entries[it.key])
        }
        assertEquals("", backAtDefault.flow.value)
        assertEquals(0, backAtDefault.setterCalls)
        assertNull(repository.entries["d"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun anUnansweredResetIsAskedAgainAfterARestartWithoutTouchingTheValues() {
        val kept = startWithKeysMissingFromTheSnapshot(3)
        assertNotNull(service.pendingReset.value)
        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        assertNull(service.pendingReset.value)
        restartService()

        start()
        debounce()
        advance(1.seconds)

        assertEquals(kept.map { it.key }, service.pendingReset.value?.keys)
        kept.forEach {
            assertEquals("v-${it.key}", it.flow.value)
            assertEquals(0, it.setterCalls)
            assertEquals(SettingsSyncKnown(json("v-${it.key}"), 5), repository.entries[it.key])
        }
        assertTrue(server.writes.isEmpty())
        assertEquals(2, server.snapshotCalls)
    }

    @Test
    fun aKeyWrittenElsewhereWhileTheResetIsOpenStaysInTheQuestionWithTheServersEntry() {
        val kept = startWithKeysMissingFromTheSnapshot(3)
        assertNotNull(service.pendingReset.value)
        val rewritten = server.foreignWrite("k1", json("new"))

        announce()

        assertEquals(2, server.snapshotCalls)
        val reset = assertNotNull(service.pendingReset.value)
        assertEquals(listOf("k1", "k2", "k3"), reset.keys)
        assertEquals(rewritten, reset.entries.first())
        assertTrue(reset.entries.drop(1).all { it.deleted })
        assertEquals("v-k1", kept[0].flow.value)
        assertEquals(0, kept[0].setterCalls)
        assertNull(service.pendingConflict.value)
        assertTrue(server.writes.isEmpty())

        service.resolveReset(keepLocal = false)
        scheduler.runCurrent()

        assertNull(service.pendingReset.value)
        assertEquals("new", kept[0].flow.value)
        assertEquals(SettingsSyncKnown(json("new"), 1), repository.entries["k1"])
        assertEquals("", kept[1].flow.value)
        assertNull(repository.entries["k2"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aServerVersionBelowTheOneThisDeviceSawAsksBeforeAdoptingEvenASingleDeletion() {
        val setting = string("a")
        persist(5, mapOf("a" to SettingsSyncKnown(json("x"), 3)))
        setting.flow.value = "x"

        start()
        debounce()
        advance(1.seconds)

        val reset = assertNotNull(service.pendingReset.value)
        assertEquals(listOf("a"), reset.keys)
        assertTrue(reset.entries.single().deleted)
        assertEquals("x", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals(SettingsSyncKnown(json("x"), 3), repository.entries["a"])
        assertTrue(server.writes.isEmpty())
        assertEquals(0L, persistedVersion())

        service.resolveReset(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("x"), 0L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
        assertEquals(SettingsSyncKnown(json("x"), 1), repository.entries["a"])
        assertEquals(1L, persistedVersion())
        assertNull(service.pendingReset.value)
    }

    private fun startWithARestoredServerHoldingAnOlderValue(): Pair<LocalSetting<String>, ClientSetting> {
        val setting = string("a")
        persist(50, mapOf("a" to SettingsSyncKnown(json("v2"), 50)))
        val old = server.seed("a", json("v1"), 20)
        setting.flow.value = "v2"
        start()
        debounce()
        advance(1.seconds)
        return setting to old
    }

    @Test
    fun aRestoredServerHoldingAnOlderValueAsksInsteadOfRevertingAndKeepingUploadsWithTheServersVersion() {
        val (setting, old) = startWithARestoredServerHoldingAnOlderValue()

        val reset = assertNotNull(service.pendingReset.value)
        assertEquals(listOf(old), reset.entries)
        assertEquals("v2", setting.flow.value)
        assertEquals(0, setting.setterCalls)
        assertEquals(SettingsSyncKnown(json("v2"), 50), repository.entries["a"])
        assertEquals(20L, persistedVersion())
        assertTrue(server.writes.isEmpty())

        service.resolveReset(keepLocal = true)
        scheduler.runCurrent()

        assertEquals(
            listOf(WriteCall(listOf(ClientSettingWrite("a", json("v2"), 20L)), ClientSettingScope.SYNCED, DEVICE, false)),
            server.writes
        )
        assertEquals(json("v2"), server.stored["a"]?.value)
        assertEquals(SettingsSyncKnown(json("v2"), 21), repository.entries["a"])
        assertEquals(21L, persistedVersion())
        assertNull(service.pendingReset.value)
    }

    @Test
    fun usingTheServersSettingsAfterARestoreAppliesItsOlderValueWithoutWriting() {
        val (setting, _) = startWithARestoredServerHoldingAnOlderValue()
        assertNotNull(service.pendingReset.value)

        service.resolveReset(keepLocal = false)
        scheduler.runCurrent()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingReset.value)
        assertEquals("v1", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertEquals(SettingsSyncKnown(json("v1"), 20), repository.entries["a"])
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun aServerVersionBelowTheOneThisDeviceSawWithNothingLostContinuesFromTheServersVersion() {
        val setting = string("a")
        val other = string("b")
        persist(50, mapOf("a" to SettingsSyncKnown(json("v1"), 50)))
        server.seed("a", json("v1"), 20)
        setting.flow.value = "v1"

        start()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingReset.value)
        assertNull(service.pendingConflict.value)
        assertEquals(SettingsSyncKnown(json("v1"), 20), repository.entries["a"])
        assertEquals(20L, persistedVersion())
        assertTrue(server.writes.isEmpty())

        server.foreignWrite("b", json("remote-b"))
        announce()

        assertEquals(listOf(20L), server.changeRequests)
        assertEquals(1, server.snapshotCalls)
        assertEquals("remote-b", other.flow.value)

        change(setting, "v2")
        debounce()

        assertEquals(listOf(ClientSettingWrite("a", json("v2"), 20L)), server.writes.single().entries)
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun aChangesPageWithAVersionBelowTheOneThisDeviceSawReadsTheSnapshotAgainAndAsks() {
        val setting = string("a")
        server.seed("a", json("v1"), 1)
        start()
        assertEquals("v1", setting.flow.value)
        server.stored.clear()
        server.version = 0

        announce(version = 0)

        assertEquals(listOf(1L), server.changeRequests)
        assertEquals(2, server.snapshotCalls)
        assertEquals(listOf("a"), service.pendingReset.value?.keys)
        assertEquals("v1", setting.flow.value)
        assertEquals(1, setting.setterCalls)
        assertEquals(0L, persistedVersion())
    }

    @Test
    fun anOwnWriteAboveTheSyncedVersionCountsAsSeenWhenTheServerFallsBehindIt() {
        val setting = string("a")
        start()
        server.foreignWrite("unregistered", json("x"))
        change(setting, "mine")
        debounce()
        assertEquals(SettingsSyncKnown(json("mine"), 2), repository.entries["a"])
        assertEquals(0L, persistedVersion())
        server.stored.remove("a")
        server.version = 1

        announce(version = 1)

        assertEquals(2, server.snapshotCalls)
        assertEquals(listOf("a"), service.pendingReset.value?.keys)
        assertEquals("mine", setting.flow.value)
        assertEquals(0, setting.setterCalls)
    }

    @Test
    fun anotherUsersPersistedStateAboveTheServersVersionIsNoRestore() {
        val setting = string("a")
        persist(9, mapOf("a" to SettingsSyncKnown(json("x"), 5)), user = "someone-else")
        setting.flow.value = "x"

        start()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingReset.value)
        assertNull(service.pendingConflict.value)
        assertEquals("x", setting.flow.value)
        assertEquals(listOf(listOf(ClientSettingWrite("a", json("x"), 0L))), server.writes.map { it.entries })
    }

    @Test
    fun aPersistedVersionWithoutAPersistedUserIsNoRestore() {
        val setting = string("a")
        settings.put(SettingKey.SettingsSyncVersion, 9L)
        setting.flow.value = "x"

        start()
        debounce()
        advance(1.seconds)

        assertNull(service.pendingReset.value)
        assertNull(service.pendingEnableChoice.value)
        assertEquals("x", setting.flow.value)
        assertEquals(listOf(listOf(ClientSettingWrite("a", json("x"), 0L))), server.writes.map { it.entries })
        assertEquals(1L, persistedVersion())
    }

    @Test
    fun aLocallyChangedKeyMissingFromTheSnapshotIsAConflictWhileTheUnchangedOnesAreTheResetQuestion() {
        val changed = string("c")
        persist(9, mapOf("c" to SettingsSyncKnown(json("c-old"), 5)))
        changed.flow.value = "c-new"
        val kept = startWithKeysMissingFromTheSnapshot(3)

        assertEquals(listOf("c"), service.pendingConflict.value?.keys)
        assertEquals(kept.map { it.key }, service.pendingReset.value?.keys)
        assertEquals("c-new", changed.flow.value)
        kept.forEach { assertEquals("v-${it.key}", it.flow.value) }
        assertTrue(server.writes.isEmpty())
    }
}
