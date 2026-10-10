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
import dev.dertyp.rpc.isTransportFailure
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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.onStart
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

data class SettingsSyncEnableChoice(
    val keys: List<String>,
    val deviceName: String?,
    val onUnlock: Boolean
)

data class SettingsSyncReset(
    val keys: List<String>,
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
        const val RESET_PROMPT_THRESHOLD = 3
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
    private var sessionScope: CoroutineScope? = null
    private var firstEnable = false
    private var accountSwitched = false
    private var enableChoice: CompletableDeferred<Boolean?>? = null
    private var lastSyncAt = 0L
    private var inFlight = 0
    private val remoteMeta = MutableStateFlow<SecretsMeta?>(null)

    private val _devices = MutableStateFlow<List<ClientDevice>>(emptyList())
    val devices: StateFlow<List<ClientDevice>> = _devices.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _status = MutableStateFlow(SettingsSyncStatus(0L, 0, false))
    val status: StateFlow<SettingsSyncStatus> = _status.asStateFlow()

    private val _pendingEnableChoice = MutableStateFlow<SettingsSyncEnableChoice?>(null)
    val pendingEnableChoice: StateFlow<SettingsSyncEnableChoice?> = _pendingEnableChoice.asStateFlow()

    private val _pendingConflict = MutableStateFlow<SettingsSyncConflict?>(null)
    val pendingConflict: StateFlow<SettingsSyncConflict?> = _pendingConflict.asStateFlow()

    private val _pendingReset = MutableStateFlow<SettingsSyncReset?>(null)
    val pendingReset: StateFlow<SettingsSyncReset?> = _pendingReset.asStateFlow()

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
        if (!sessionActive || _pendingEnableChoice.value?.onUnlock == false) return
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

    fun resolveEnableChoice(keepLocal: Boolean) {
        _pendingEnableChoice.value = null
        enableChoice?.complete(keepLocal)
        enableChoice = null
    }

    fun cancelEnableChoice() {
        val onUnlock = _pendingEnableChoice.value?.onUnlock ?: return
        clearEnableChoice()
        if (!onUnlock) Config.setIsSettingsSyncEnabled(false)
    }

    fun resolveConflict(keepLocal: Boolean) {
        val conflict = _pendingConflict.value ?: return
        scope.launch {
            mutex.withLock {
                if (deviceId == null || _pendingConflict.value != conflict) return@withLock
                runCatching {
                    val active = activeSpecs().associateBy { it.key }
                    if (keepLocal) {
                        val writes = conflict.entries.mapNotNull { entry ->
                            val spec = active[entry.key] ?: return@mapNotNull null
                            writeFor(spec, entry.version, entry.value)
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
                            if (entry.key !in registry.byKey) return@forEach
                            remember(entry.key, entry)
                            active[entry.key]?.applyRemote(if (entry.deleted) null else entry.value)
                        }
                    }
                    _pendingConflict.value = null
                    persistState()
                    markSynced()
                }.onFailure { report(it) }
            }
        }
    }

    fun resolveReset(keepLocal: Boolean) {
        val reset = _pendingReset.value ?: return
        scope.launch {
            mutex.withLock {
                if (deviceId == null || _pendingReset.value != reset) return@withLock
                runCatching {
                    val active = activeSpecs().associateBy { it.key }
                    val writes = mutableListOf<ClientSettingWrite>()
                    for (entry in reset.entries) {
                        val spec = active[entry.key] ?: continue
                        val current = entry.takeUnless { it.deleted }
                        val write = writeFor(spec, current?.version ?: 0L, current?.value)?.takeIf { keepLocal }
                        if (write != null) {
                            writes += write
                        } else {
                            remember(entry.key, current)
                            spec.applyRemote(current?.value)
                        }
                    }
                    push(writes, retry = true)
                    _pendingReset.value = null
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
                deviceId == id && unlock(key)
            }

            SecretsLockState.NEEDS_PASSPHRASE -> {
                val meta = remoteMeta.value ?: return false
                val key = withContext(dispatchers.default) { cipher.deriveKey(passphrase, meta) }
                if (!cipher.verify(meta, key)) return false
                deviceId == id && unlock(key)
            }

            SecretsLockState.UNLOCKED -> true
            SecretsLockState.DISABLED -> false
        }
    }

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
            val id = identity.deviceId
            mutex.withLock {
                deviceId = id
                loadPersistedState(userId)
            }
            publishStatus()

            runCatching {
                clientSettingsService.registerDevice(id, deviceName(), identity.platform)
            }.onFailure { report(it) }

            var snapshot = retrying { clientSettingsService.getSnapshot(id) }
            var keepLocal = false
            if (firstEnable) {
                val differing = mutex.withLock { enableDifferences(snapshot) }
                if (differing.isNotEmpty()) {
                    val deviceName = resolveDeviceName(differing.first().modifiedByDeviceId)
                    keepLocal = awaitEnableChoice(differing.map { it.key }, deviceName, onUnlock = false) ?: return
                    snapshot = retrying { clientSettingsService.getSnapshot(id) }
                }
            }
            mutex.withLock { tracked { reconcile(snapshot, keepLocal) } }

            coroutineScope {
                sessionScope = this
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
            sessionScope = null
            clearEnableChoice()
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
        }
        persistedKnown = known
        firstEnable = persistedUserId == null && known.isEmpty()
        accountSwitched = persistedUserId != null && persistedUserId != userId
    }

    private suspend fun awaitEnableChoice(keys: List<String>, deviceName: String?, onUnlock: Boolean): Boolean? {
        val decision = CompletableDeferred<Boolean?>()
        enableChoice = decision
        _pendingEnableChoice.value = SettingsSyncEnableChoice(keys, deviceName, onUnlock)
        return decision.await()
    }

    private fun clearEnableChoice() {
        if (_pendingEnableChoice.value?.onUnlock == true) cipher.forget()
        enableChoice?.complete(null)
        enableChoice = null
        _pendingEnableChoice.value = null
    }

    private fun enableDifferences(snapshot: ClientSettingsSnapshot): List<ClientSetting> {
        val entries = snapshot.entries.filter { it.scope == SCOPE }.associateBy { it.key }
        val meta = entries[SecretsCipher.META_KEY]?.value?.let { cipher.parseMeta(it) }
        val unlocked = computeLock(Config.isSecretsSyncEnabled.value, cipher.key.value, meta) == SecretsLockState.UNLOCKED
        val specs = if (unlocked) registry.all else registry.general
        return specs.mapNotNull { spec ->
            val server = entries[spec.key] ?: return@mapNotNull null
            if (spec.isDefault() || spec.matchesLocal(server.value)) null else server
        }
    }

    private suspend fun observeLoop(id: String) {
        var resubscribing = false
        while (currentCoroutineContext().isActive) {
            if (resubscribing) {
                mutex.withLock {
                    runCatching { tracked { pull() } }.onFailure { report(it) }
                }
            }
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
            resubscribing = true
            delay(STREAM_RETRY_DELAY)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun outboundLoop() {
        registry.changes()
            .debounce(OUTBOUND_DEBOUNCE)
            .onStart { emit(Unit) }
            .collect {
                var backoff = INITIAL_BACKOFF
                while (true) {
                    val failure = runCatching { mutex.withLock { tracked { pushPending() } } }.exceptionOrNull() ?: break
                    if (failure is CancellationException) throw failure
                    report(failure)
                    if (!failure.isTransportFailure()) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                }
            }
    }

    private suspend fun lockLoop() {
        combine(Config.isSecretsSyncEnabled, cipher.key, remoteMeta) { enabled, key, meta ->
            computeLock(enabled, key, meta)
        }.collect { lock ->
            if (lock == SecretsLockState.UNLOCKED && _secretsLockState.value != SecretsLockState.UNLOCKED) {
                val key = cipher.key.value ?: return@collect
                if (enableChoice == null) mutex.withLock { unlock(key) }
            } else {
                _secretsLockState.value = lock
                publishStatus()
            }
        }
    }

    private suspend fun unlock(key: DerivedKey): Boolean {
        val session = sessionScope?.takeIf { it.isActive } ?: return false
        val differing = if (accountSwitched) emptyList() else cipher.withKey(key) { unlockDifferences() }
        if (differing.isEmpty()) {
            applyUnlock(key, keepLocal = false)
            return true
        }
        session.launch {
            val keepLocal = awaitEnableChoice(differing, deviceName = null, onUnlock = true)
            mutex.withLock {
                if (keepLocal == null) {
                    cipher.forget()
                    refreshLockState()
                } else {
                    applyUnlock(key, keepLocal)
                }
            }
        }
        return true
    }

    private suspend fun applyUnlock(key: DerivedKey, keepLocal: Boolean) {
        _secretsLockState.value = SecretsLockState.UNLOCKED
        cipher.remember(key)
        publishStatus()
        runCatching {
            tracked {
                reconcileSecretsFromKnown(keepLocal)
                pushPending()
            }
        }.onFailure { report(it) }
    }

    private fun unlockDifferences(): List<String> = registry.secrets.mapNotNull { spec ->
        val entry = known[spec.key] ?: return@mapNotNull null
        val differs = !spec.isDefault() && (entry.value == null || !spec.matchesLocal(entry.value))
        if (differs) spec.key else null
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

    private val conflictedKeys: Set<String>
        get() = _pendingConflict.value?.keys?.toSet().orEmpty()

    private val frozenKeys: Set<String>
        get() = conflictedKeys + _pendingReset.value?.keys.orEmpty()

    private val highestSeenVersion: Long
        get() = maxOf(syncedVersion, known.values.maxOfOrNull { it.version } ?: 0L)

    private fun hasUnconfirmedLocalChange(spec: SyncedSetting<*>): Boolean {
        val entry = known[spec.key] ?: return !spec.isDefault()
        return localDiffers(spec, entry)
    }

    private fun pendingWrites(): List<ClientSettingWrite> {
        val frozen = frozenKeys
        return activeSpecs().mapNotNull { spec ->
            if (spec.key in frozen || !hasUnconfirmedLocalChange(spec)) return@mapNotNull null
            val entry = known[spec.key]
            writeFor(spec, entry?.version ?: 0L, entry?.value)
        }
    }

    private suspend fun reconcile(snapshot: ClientSettingsSnapshot, keepLocal: Boolean = false) {
        val entries = snapshot.entries.filter { it.scope == SCOPE }.associateBy { it.key }
        applyMeta(entries[SecretsCipher.META_KEY]?.value)

        val restored = snapshot.syncedVersion < highestSeenVersion
        val merged = known.toMutableMap()
        val active = activeSpecs()
        val activeKeys = active.map { it.key }.toSet()
        val conflicted = conflictedKeys
        val writes = mutableListOf<ClientSettingWrite>()
        val conflicts = mutableListOf<ClientSetting>()
        val deletions = mutableListOf<SyncedSetting<*>>()
        val lost = mutableListOf<ClientSetting>()

        for (spec in active) {
            val prior = merged[spec.key]
            val server = entries[spec.key]
            if (spec.key in conflicted) {
                conflicts += server ?: ClientSetting(key = spec.key, deleted = true, version = 0L, modifiedAt = 0L, scope = SCOPE)
                continue
            }
            val localChanged = prior != null && localDiffers(spec, prior)
            when {
                server == null -> when {
                    prior?.value == null -> {
                        merged.remove(spec.key)
                        if (!spec.isDefault()) spec.encodeLocal()?.let { writes += ClientSettingWrite(spec.key, it, 0L) }
                    }

                    spec.isDefault() -> merged.remove(spec.key)

                    !localChanged -> {
                        deletions += spec
                        lost += ClientSetting(key = spec.key, deleted = true, version = 0L, modifiedAt = 0L, scope = SCOPE)
                    }

                    else -> conflicts += ClientSetting(key = spec.key, deleted = true, version = 0L, modifiedAt = 0L, scope = SCOPE)
                }

                prior == null && keepLocal && !spec.isDefault() && !spec.matchesLocal(server.value) -> {
                    merged[spec.key] = SettingsSyncKnown(server.value, server.version)
                    spec.encodeLocal()?.let { writes += ClientSettingWrite(spec.key, it, server.version) }
                }

                prior == null && !firstEnable && !accountSwitched && !spec.isDefault() && !spec.matchesLocal(server.value) ->
                    conflicts += server

                !localChanged -> {
                    val value = if (server.deleted) null else server.value
                    when {
                        spec.matchesLocal(value) -> merged[spec.key] = SettingsSyncKnown(server.value, server.version)

                        restored && prior != null && server.version < prior.version -> lost += server

                        else -> {
                            spec.applyRemote(value)
                            merged[spec.key] = SettingsSyncKnown(server.value, server.version)
                        }
                    }
                }

                server.version == prior.version -> writeFor(spec, prior.version, prior.value)?.let { writes += it }

                spec.matchesLocal(server.value) -> merged[spec.key] = SettingsSyncKnown(server.value, server.version)

                else -> conflicts += server
            }
        }

        for (spec in registry.secrets) {
            if (spec.key in activeKeys || spec.key in conflicted) continue
            val server = entries[spec.key]
            when {
                server != null -> merged[spec.key] = SettingsSyncKnown(server.value, server.version)
                merged[spec.key]?.value != null -> merged[spec.key] = SettingsSyncKnown(null, 0L)
            }
        }

        val asked = _pendingReset.value?.keys.orEmpty()
        if (lost.isNotEmpty() && (restored || deletions.size >= RESET_PROMPT_THRESHOLD || deletions.any { it.key in asked })) {
            _pendingReset.value = SettingsSyncReset(lost.map { it.key }, lost)
        } else {
            _pendingReset.value = null
            for (spec in deletions) {
                spec.applyRemote(null)
                merged.remove(spec.key)
            }
        }

        syncedVersion = snapshot.syncedVersion
        known = merged
        addConflicts(conflicts)
        runCatching { push(writes, retry = true) }.onFailure {
            if (it is CancellationException) throw it
            report(it)
        }
        persistState()
        markSynced()
    }

    private suspend fun reconcileSecretsFromKnown(keepLocal: Boolean) {
        val frozen = frozenKeys
        for (spec in registry.secrets) {
            val entry = known[spec.key] ?: continue
            if (spec.key in frozen || (keepLocal && !spec.isDefault())) continue
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
        try {
            push(writes, retry = true)
        } catch (e: Throwable) {
            if (e is CancellationException || e.isTransportFailure() || writes.size == 1) throw e
            report(e)
            for (write in writes) {
                runCatching { push(listOf(write), retry = false) }.onFailure {
                    if (it is CancellationException) throw it
                    report(it)
                }
            }
        }
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
                    val spec = registry.byKey[conflict.key]
                    if (current != null && spec != null && !spec.matchesLocal(current.value)) {
                        real += current
                    } else {
                        remember(conflict.key, current)
                    }
                }
                addConflicts(real)
                if (retry) push(pendingWrites(), retry = false)
            }
        }
    }

    private suspend fun pull() {
        val id = deviceId ?: return
        var cursor = syncedVersion
        val seen = highestSeenVersion
        while (true) {
            val page = clientSettingsService.getChanges(SCOPE, cursor, null, CHANGES_PAGE)
            if (page.fullResync || page.version < seen) {
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
        if (entry.key in conflictedKeys) {
            addConflicts(listOf(entry))
            return
        }
        _pendingReset.value?.let { reset ->
            if (entry.key in reset.keys) {
                val remaining = reset.entries.filter { it.key != entry.key }
                _pendingReset.value = if (remaining.isEmpty()) null else SettingsSyncReset(remaining.map { it.key }, remaining)
            }
        }
        val locked = spec.group == SyncGroup.SECRETS && _secretsLockState.value != SecretsLockState.UNLOCKED
        val value = if (entry.deleted) null else entry.value
        when {
            locked || entry.modifiedByDeviceId == deviceId -> remember(entry.key, entry)

            !hasUnconfirmedLocalChange(spec) || spec.matchesLocal(value) -> {
                remember(entry.key, entry)
                spec.applyRemote(value)
            }

            else -> addConflicts(listOf(entry))
        }
    }

    private suspend fun addConflicts(entries: List<ClientSetting>) {
        if (entries.isEmpty()) return
        val existing = _pendingConflict.value
        val merged = (entries + existing?.entries.orEmpty()).distinctBy { it.key }
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

    private suspend fun reset() = mutex.withLock {
        deviceId = null
        syncedVersion = 0L
        known = emptyMap()
        persistedKnown = emptyMap()
        stateUserId = null
        remoteMeta.value = null
        clearEnableChoice()
        _secretsLockState.value = SecretsLockState.DISABLED
        _pendingConflict.value = null
        _pendingReset.value = null
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
        }.onSuccess { persistedKnown = current }.onFailure { report(it) }

        settings.put(SettingKey.SettingsSyncVersion, syncedVersion)
        stateUserId?.let { settings.put(SettingKey.SettingsSyncUserId, it) }
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
