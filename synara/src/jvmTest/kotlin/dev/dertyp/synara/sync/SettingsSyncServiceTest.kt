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
import dev.dertyp.synara.utils.secureRandomBytes
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        writeFailure?.let { throw it }
        writeHandler?.let { return it(call) }
        if (!force) {
            val conflicts = entries.mapNotNull { write ->
                val current = stored[write.key]
                val currentVersion = current?.takeUnless { it.deleted }?.version ?: 0L
                if (write.baseVersion == currentVersion) null else ClientSettingConflict(write.key, write.baseVersion, current)
            }
            if (conflicts.isNotEmpty()) return ClientSettingsWriteResult.Conflict(version, conflicts)
        }
        version++
        val written = entries.map { write ->
            settingEntry(write.key, write.value, version, device, scope).also { stored[write.key] = it }
        }
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
        return changes
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

    override suspend fun getAll(): Map<String, SettingsSyncKnown> {
        getAllCalls++
        return entries.toMap()
    }

    override suspend fun putAll(entries: Map<String, SettingsSyncKnown>) {
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
        assertFalse(cipher.isUnlocked)
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
        assertFalse(cipher.isUnlocked)
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
}
