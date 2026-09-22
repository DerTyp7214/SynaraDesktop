package dev.dertyp.synara.sync

import com.russhwolf.settings.Settings
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.ClientDevice
import dev.dertyp.data.ClientSetting
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsSnapshot
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IClientSettingsService
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.SettingsSyncKnown
import dev.dertyp.synara.db.SettingsSyncRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SETTINGS_SYNC_TAG = LogTag("settings-sync")

data class SettingsSyncStatus(
    val lastSyncAt: Long,
    val pendingKeys: Int,
    val isSyncing: Boolean
)

data class SettingsSyncConflict(
    val keys: List<String>,
    val deviceName: String?,
    val entries: List<ClientSetting>
)

enum class SecretsLockState { DISABLED, NEEDS_SETUP, NEEDS_PASSPHRASE, UNLOCKED }

/**
 * Keeps the settings of this installation in step with the synced scope of the server: a three way
 * merge on every session start, incremental pulls driven by change notifications, debounced pushes
 * with per key base versions, and a user facing conflict when both sides changed the same key while
 * this device was away.
 */
class SettingsSyncService(
    private val clientSettingsService: IClientSettingsService,
    private val identity: DeviceIdentity,
    private val settings: Settings,
    private val repository: SettingsSyncRepository,
    private val registry: SyncedSettingsRegistry,
    private val cipher: SecretsCipher,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) {
    companion object {
        val OUTBOUND_DEBOUNCE = 400.milliseconds
        const val CHANGES_PAGE = 500
        val SCOPE = ClientSettingScope.SYNCED
        val INITIAL_BACKOFF = 1.seconds
        val MAX_BACKOFF = 30.seconds
        private val STREAM_RETRY_DELAY = 5.seconds
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var started = false
    private val mutex = Mutex()

    private var deviceId: String? = null
    private var stateUserId: String? = null
    private var syncedVersion = 0L
    private var known: Map<String, SettingsSyncKnown> = emptyMap()
    private var persistedKnown: Map<String, SettingsSyncKnown> = emptyMap()
    private var sessionActive = false
    private var lastSyncAt = 0L
    private var inFlight = 0
    private val remoteMeta = MutableStateFlow<SecretsMeta?>(null)

    private val _devices = MutableStateFlow<List<ClientDevice>>(emptyList())
    val devices: StateFlow<List<ClientDevice>> = _devices.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _status = MutableStateFlow(SettingsSyncStatus(0L, 0, false))
    val status: StateFlow<SettingsSyncStatus> = _status.asStateFlow()

    private val _pendingConflict = MutableStateFlow<SettingsSyncConflict?>(null)
    val pendingConflict: StateFlow<SettingsSyncConflict?> = _pendingConflict.asStateFlow()

    private val _secretsLockState = MutableStateFlow(SecretsLockState.DISABLED)
    val secretsLockState: StateFlow<SecretsLockState> = _secretsLockState.asStateFlow()

    val platformDeviceName: String get() = identity.platformDeviceName

    val currentDeviceId: String get() = identity.deviceId

    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                Config.isSettingsSyncEnabled,
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState
            ) { enabled, reachable, connection ->
                Gate(
                    enabled = enabled,
                    reachable = reachable,
                    authenticated = connection == RpcServiceManager.ConnectionState.Authenticated,
                    userId = rpcServiceManager.userId
                )
            }.distinctUntilChanged().collectLatest { gate ->
                if (gate.isActive) {
                    runCatching { session(gate.userId) }.onFailure { report(it) }
                } else {
                    reset()
                }
            }
        }
    }

    fun refreshDevices() {
        scope.launch {
            _isBusy.value = true
            publishStatus()
            try {
                _devices.value = clientSettingsService.getDevices()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                report(e)
            } finally {
                _isBusy.value = false
                publishStatus()
            }
        }
    }

    fun deleteDevice(deviceId: String) {
        scope.launch {
            _isBusy.value = true
            publishStatus()
            try {
                clientSettingsService.deleteDevice(deviceId)
                _devices.value = clientSettingsService.getDevices()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                report(e)
            } finally {
                _isBusy.value = false
                publishStatus()
            }
        }
    }

    fun syncNow() {
        if (!sessionActive) return
        scope.launch {
            _isBusy.value = true
            publishStatus()
            try {
                mutex.withLock {
                    runCatching {
                        pull()
                        pushPending()
                    }.onFailure { report(it) }
                }
            } finally {
                _isBusy.value = false
                publishStatus()
            }
        }
    }

    fun resolveConflict(keepLocal: Boolean) {
        val conflict = _pendingConflict.value ?: return
        scope.launch {
            mutex.withLock {
                runCatching {
                    if (keepLocal) {
                        val writes = conflict.keys.mapNotNull { key ->
                            val spec = registry.byKey[key] ?: return@mapNotNull null
                            val entry = known[key]
                            writeFor(spec, entry?.version ?: 0L, entry?.value)
                        }
                        if (writes.isNotEmpty()) {
                            when (val result = clientSettingsService.setSettings(writes, SCOPE, deviceId, force = true)) {
                                is ClientSettingsWriteResult.Ok -> fold(result)
                                is ClientSettingsWriteResult.Conflict ->
                                    result.conflicts.forEach { remember(it.key, it.current) }
                            }
                        }
                    } else {
                        conflict.entries.forEach { entry ->
                            val spec = registry.byKey[entry.key] ?: return@forEach
                            remember(entry.key, entry)
                            spec.applyRemote(if (entry.deleted) null else entry.value)
                        }
                    }
                    _pendingConflict.value = null
                    persistState()
                    markSynced()
                }.onFailure { report(it) }
            }
        }
    }

    suspend fun setPassphrase(passphrase: String): Boolean = mutex.withLock {
        val id = deviceId ?: return false
        when (_secretsLockState.value) {
            SecretsLockState.NEEDS_SETUP -> {
                var (meta, key) = withContext(dispatchers.default) { cipher.createMeta(passphrase) }
                val write = ClientSettingWrite(SecretsCipher.META_KEY, AppJson.encodeToString(meta), 0L)
                val result = runCatching { clientSettingsService.setSettings(listOf(write), SCOPE, id, force = false) }
                    .getOrElse {
                        report(it)
                        return false
                    }
                when (result) {
                    is ClientSettingsWriteResult.Ok -> {
                        remoteMeta.value = meta
                        fold(result)
                        persistState()
                    }

                    is ClientSettingsWriteResult.Conflict -> {
                        val current = result.conflicts.firstOrNull()?.current
                        val serverMeta = current?.value?.let { cipher.parseMeta(it) } ?: return false
                        remoteMeta.value = serverMeta
                        remember(current.key, current)
                        key = withContext(dispatchers.default) { cipher.deriveKey(passphrase, serverMeta) }
                        if (!cipher.verify(serverMeta, key)) return false
                    }
                }
                cipher.remember(key)
                true
            }

            SecretsLockState.NEEDS_PASSPHRASE -> {
                val meta = remoteMeta.value ?: return false
                val key = withContext(dispatchers.default) { cipher.deriveKey(passphrase, meta) }
                if (!cipher.verify(meta, key)) return false
                cipher.remember(key)
                true
            }

            SecretsLockState.UNLOCKED -> true
            SecretsLockState.DISABLED -> false
        }
    }

    suspend fun enterPassphrase(passphrase: String): Boolean = setPassphrase(passphrase)

    fun forgetPassphrase() {
        scope.launch { cipher.forget() }
    }

    fun deviceName(): String = identity.deviceName()

    private data class Gate(
        val enabled: Boolean,
        val reachable: Boolean,
        val authenticated: Boolean,
        val userId: String?
    ) {
        val isActive: Boolean get() = enabled && reachable && authenticated
    }

    private suspend fun session(userId: String?) {
        sessionActive = true
        try {
            coroutineScope {
                val id = identity.deviceId
                deviceId = id
                loadPersistedState(userId)
                publishStatus()

                runCatching {
                    clientSettingsService.registerDevice(id, deviceName(), identity.platform)
                }.onFailure { report(it) }

                val snapshot = retrying { clientSettingsService.getSnapshot(id) }
                mutex.withLock { tracked { reconcile(snapshot) } }

                launch {
                    Config.queueSyncDeviceName
                        .map { deviceName() }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect { name ->
                            runCatching {
                                clientSettingsService.registerDevice(id, name, identity.platform)
                            }.onFailure { report(it) }
                        }
                }
                launch { observeLoop(id) }
                launch { outboundLoop() }
                launch { lockLoop() }
            }
        } finally {
            sessionActive = false
        }
    }

    private suspend fun loadPersistedState(userId: String?) {
        val persistedUserId = settings.getOrNull(SettingKey.SettingsSyncUserId)
        stateUserId = userId
        lastSyncAt = settings.get(SettingKey.SettingsSyncLastAt, 0L)
        remoteMeta.value = null

        if (persistedUserId == userId) {
            syncedVersion = settings.get(SettingKey.SettingsSyncVersion, 0L)
            known = runCatching { repository.getAll() }.getOrElse {
                report(it)
                emptyMap()
            }
        } else {
            syncedVersion = 0L
            known = emptyMap()
            runCatching { repository.clear() }.onFailure { report(it) }
            settings.put(SettingKey.SettingsSyncVersion, 0L)
            settings.put(SettingKey.SettingsSyncUserId, userId)
        }
        persistedKnown = known
    }

    private suspend fun observeLoop(id: String) {
        while (currentCoroutineContext().isActive) {
            runCatching {
                clientSettingsService.observeSettings().collect { change ->
                    if (change.scope != SCOPE) return@collect
                    if (change.modifiedByDeviceId == id) {
                        mutex.withLock {
                            if (change.version == syncedVersion + 1) {
                                syncedVersion = change.version
                                persistState()
                            }
                        }
                    } else {
                        mutex.withLock {
                            runCatching { tracked { pull() } }.onFailure { report(it) }
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                report(it)
            }
            delay(STREAM_RETRY_DELAY)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun outboundLoop() {
        registry.changes()
            .debounce(OUTBOUND_DEBOUNCE)
            .collect {
                mutex.withLock {
                    runCatching { tracked { pushPending() } }.onFailure { report(it) }
                }
            }
    }

    private suspend fun lockLoop() {
        var previous = _secretsLockState.value
        combine(Config.isSecretsSyncEnabled, cipher.key, remoteMeta) { enabled, key, meta ->
            computeLock(enabled, key, meta)
        }.collect { lock ->
            _secretsLockState.value = lock
            publishStatus()
            if (lock == SecretsLockState.UNLOCKED && previous != SecretsLockState.UNLOCKED) {
                mutex.withLock {
                    runCatching {
                        tracked {
                            reconcileSecretsFromKnown()
                            pushPending()
                        }
                    }.onFailure { report(it) }
                }
            }
            previous = lock
        }
    }

    private fun computeLock(enabled: Boolean, key: DerivedKey?, meta: SecretsMeta?): SecretsLockState = when {
        !enabled -> SecretsLockState.DISABLED
        meta == null -> SecretsLockState.NEEDS_SETUP
        key == null || !cipher.verify(meta, key) -> SecretsLockState.NEEDS_PASSPHRASE
        else -> SecretsLockState.UNLOCKED
    }

    private fun refreshLockState() {
        _secretsLockState.value =
            computeLock(Config.isSecretsSyncEnabled.value, cipher.key.value, remoteMeta.value)
    }

    private fun applyMeta(value: String?) {
        val meta = value?.let { cipher.parseMeta(it) }
        remoteMeta.value = meta
        val key = cipher.key.value
        if (meta != null && key != null && !cipher.verify(meta, key)) cipher.forget()
        refreshLockState()
    }

    private fun activeSpecs(): List<SyncedSetting<*>> =
        if (_secretsLockState.value == SecretsLockState.UNLOCKED) registry.all else registry.general

    private fun localDiffers(spec: SyncedSetting<*>, entry: SettingsSyncKnown): Boolean =
        if (entry.value == null) !spec.isDefault() else !spec.matchesLocal(entry.value)

    private fun writeFor(spec: SyncedSetting<*>, baseVersion: Long, knownValue: String?): ClientSettingWrite? {
        val local = spec.encodeLocal()
        return when {
            local != null -> ClientSettingWrite(spec.key, local, baseVersion)
            knownValue != null -> ClientSettingWrite(spec.key, null, baseVersion)
            else -> null
        }
    }

    private fun pendingWrites(): List<ClientSettingWrite> {
        val conflicted = _pendingConflict.value?.keys?.toSet().orEmpty()
        return activeSpecs().mapNotNull { spec ->
            if (spec.key in conflicted) return@mapNotNull null
            val entry = known[spec.key]
            when {
                entry == null ->
                    if (spec.isDefault()) null else spec.encodeLocal()?.let { ClientSettingWrite(spec.key, it, 0L) }

                localDiffers(spec, entry) -> writeFor(spec, entry.version, entry.value)
                else -> null
            }
        }
    }

    private suspend fun reconcile(snapshot: ClientSettingsSnapshot) {
        val entries = snapshot.entries.filter { it.scope == SCOPE }.associateBy { it.key }
        applyMeta(entries[SecretsCipher.META_KEY]?.value)

        val merged = known.toMutableMap()
        val active = activeSpecs()
        val activeKeys = active.map { it.key }.toSet()
        val writes = mutableListOf<ClientSettingWrite>()
        val conflicts = mutableListOf<ClientSetting>()

        for (spec in active) {
            val prior = merged[spec.key]
            val server = entries[spec.key]
            val localChanged = prior != null && localDiffers(spec, prior)
            when {
                server == null -> {
                    if (prior == null) {
                        if (!spec.isDefault()) spec.encodeLocal()?.let { writes += ClientSettingWrite(spec.key, it, 0L) }
                    } else {
                        merged.remove(spec.key)
                    }
                }

                !localChanged -> {
                    val value = if (server.deleted) null else server.value
                    if (!spec.matchesLocal(value)) spec.applyRemote(value)
                    merged[spec.key] = SettingsSyncKnown(server.value, server.version)
                }

                server.version == prior.version -> writeFor(spec, prior.version, prior.value)?.let { writes += it }

                spec.matchesLocal(server.value) -> merged[spec.key] = SettingsSyncKnown(server.value, server.version)

                else -> {
                    merged[spec.key] = SettingsSyncKnown(server.value, server.version)
                    conflicts += server
                }
            }
        }

        for (spec in registry.secrets) {
            if (spec.key in activeKeys) continue
            val server = entries[spec.key]
            if (server == null) merged.remove(spec.key) else merged[spec.key] = SettingsSyncKnown(server.value, server.version)
        }

        syncedVersion = snapshot.syncedVersion
        known = merged
        addConflicts(conflicts)
        push(writes, retry = true)
        persistState()
        markSynced()
    }

    private suspend fun reconcileSecretsFromKnown() {
        for (spec in registry.secrets) {
            val entry = known[spec.key] ?: continue
            if (!spec.matchesLocal(entry.value)) spec.applyRemote(entry.value)
        }
    }

    private suspend fun pushPending() {
        if (deviceId == null) return
        val writes = pendingWrites()
        if (writes.isEmpty()) {
            publishStatus()
            return
        }
        push(writes, retry = true)
        persistState()
        markSynced()
    }

    private suspend fun push(writes: List<ClientSettingWrite>, retry: Boolean) {
        if (writes.isEmpty()) return
        when (val result = clientSettingsService.setSettings(writes, SCOPE, deviceId, force = false)) {
            is ClientSettingsWriteResult.Ok -> fold(result)

            is ClientSettingsWriteResult.Conflict -> {
                val real = mutableListOf<ClientSetting>()
                result.conflicts.forEach { conflict ->
                    val current = conflict.current
                    remember(conflict.key, current)
                    val spec = registry.byKey[conflict.key] ?: return@forEach
                    if (current != null && !spec.matchesLocal(current.value)) real += current
                }
                addConflicts(real)
                if (retry) push(pendingWrites(), retry = false)
            }
        }
    }

    private suspend fun pull() {
        val id = deviceId ?: return
        var cursor = syncedVersion
        while (true) {
            val page = clientSettingsService.getChanges(SCOPE, cursor, null, CHANGES_PAGE)
            if (page.fullResync) {
                reconcile(clientSettingsService.getSnapshot(id))
                return
            }
            for (entry in page.entries) applyChange(entry)
            cursor = page.entries.lastOrNull()?.version ?: page.version
            if (!page.hasMore) {
                syncedVersion = maxOf(cursor, page.version)
                break
            }
        }
        persistState()
        markSynced()
    }

    private suspend fun applyChange(entry: ClientSetting) {
        if (entry.scope != SCOPE) return
        if (entry.key == SecretsCipher.META_KEY) {
            applyMeta(if (entry.deleted) null else entry.value)
            return
        }
        val spec = registry.byKey[entry.key] ?: return
        val active = spec.group == SyncGroup.GENERAL || _secretsLockState.value == SecretsLockState.UNLOCKED
        if (active) {
            remember(entry.key, entry)
            spec.applyRemote(if (entry.deleted) null else entry.value)
        } else if (spec.group == SyncGroup.SECRETS) {
            remember(entry.key, entry)
        }
    }

    private suspend fun addConflicts(entries: List<ClientSetting>) {
        if (entries.isEmpty()) return
        val existing = _pendingConflict.value
        val merged = (existing?.entries.orEmpty() + entries).distinctBy { it.key }
        val deviceName = existing?.deviceName ?: resolveDeviceName(entries.first().modifiedByDeviceId)
        _pendingConflict.value = SettingsSyncConflict(merged.map { it.key }, deviceName, merged)
        publishStatus()
    }

    private suspend fun resolveDeviceName(id: String?): String? {
        if (id == null) return null
        _devices.value.firstOrNull { it.deviceId == id }?.let { return it.name.takeIf { name -> name.isNotBlank() } }
        runCatching { clientSettingsService.getDevices() }
            .onSuccess { _devices.value = it }
            .onFailure { report(it) }
        return _devices.value.firstOrNull { it.deviceId == id }?.name?.takeIf { it.isNotBlank() }
    }

    private fun fold(result: ClientSettingsWriteResult.Ok) {
        result.entries.forEach { remember(it.key, it) }
        if (result.version == syncedVersion + 1) syncedVersion = result.version
    }

    private fun remember(key: String, entry: ClientSetting?) {
        known = if (entry == null) {
            known - key
        } else {
            known + (key to SettingsSyncKnown(entry.value, entry.version))
        }
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var backoff = INITIAL_BACKOFF
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                report(e)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private suspend fun <T> tracked(block: suspend () -> T): T {
        inFlight++
        publishStatus()
        try {
            return block()
        } finally {
            inFlight--
            publishStatus()
        }
    }

    private fun reset() {
        deviceId = null
        syncedVersion = 0L
        known = emptyMap()
        persistedKnown = emptyMap()
        stateUserId = null
        inFlight = 0
        remoteMeta.value = null
        _secretsLockState.value = SecretsLockState.DISABLED
        _pendingConflict.value = null
        _devices.value = emptyList()
        publishStatus()
    }

    private suspend fun persistState() {
        val current = known
        val previous = persistedKnown
        val changed = current.filter { (key, value) -> previous[key] != value }
        val removed = previous.keys - current.keys
        runCatching {
            if (removed.isNotEmpty()) repository.remove(removed)
            if (changed.isNotEmpty()) repository.putAll(changed)
        }.onFailure { report(it) }
        persistedKnown = current

        settings.put(SettingKey.SettingsSyncVersion, syncedVersion)
        settings.put(SettingKey.SettingsSyncUserId, stateUserId)
    }

    private fun markSynced() {
        lastSyncAt = currentTimeMillis()
        settings.put(SettingKey.SettingsSyncLastAt, lastSyncAt)
        publishStatus()
    }

    private fun publishStatus() {
        _status.value = SettingsSyncStatus(
            lastSyncAt = lastSyncAt,
            pendingKeys = if (sessionActive && deviceId != null) pendingWrites().size else 0,
            isSyncing = _isBusy.value || inFlight > 0
        )
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(SETTINGS_SYNC_TAG, error.message ?: "Settings sync failed", error)
    }
}
