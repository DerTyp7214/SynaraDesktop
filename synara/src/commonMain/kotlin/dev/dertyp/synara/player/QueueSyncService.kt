package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.rpc.isTransportFailure
import dev.dertyp.services.IClientRequestService
import dev.dertyp.services.IQueueService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.toPlatformUUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val QUEUE_SYNC_TAG = LogTag("queue-sync")

data class QueueSyncStatus(
    val lastSyncAt: Long,
    val uploadPending: Boolean,
    val behindDevice: String?
)

sealed class QueueSyncEvent {
    data class Merged(val deviceName: String?) : QueueSyncEvent()
}

class QueueSyncService(
    private val queueService: IQueueService,
    private val clientRequestService: IClientRequestService,
    private val presenceService: PresenceService,
    private val identity: DeviceIdentity,
    private val playerModel: PlayerModel,
    private val songCache: SongCache,
    private val settings: Settings,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) {
    companion object {
        const val PAGE_SIZE = 500
        const val UPLOAD_CHUNK = 500
        const val SONG_INCLUDE_LIMIT = 600
        const val PULL_ATTEMPTS = 3
        const val MAX_CONFLICTS = 3
        val CURRENT_DEBOUNCE = 500.milliseconds
        private val STREAM_RETRY_DELAY = 5.seconds
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var started = false
    private val mutex = Mutex()
    private val lane = Mutex()
    private var epoch = 0L
    private class Pending(val serial: Long, val change: PlayerModel.QueueChange)

    private class ReadFailure(cause: Throwable) : RuntimeException(cause)

    private enum class Followup { None, Ask, Pull, PullKeepingCurrent, Rebase, Upload }

    private val outbound = Channel<Pending>(Channel.UNLIMITED)
    private val held = ArrayDeque<PlayerModel.QueueChange>()
    private var unacknowledged: PlayerModel.QueueChange? = null
    private val accepting = MutableStateFlow(false)
    private val coveredSerial = MutableStateFlow(0L)

    private val _syncedVersion = MutableStateFlow(0L)
    val syncedVersionFlow: StateFlow<Long> = _syncedVersion.asStateFlow()

    private var syncedVersion: Long
        get() = _syncedVersion.value
        set(value) {
            _syncedVersion.value = value
        }

    private val receivedSerial = MutableStateFlow(0L)
    private val enqueued = MutableStateFlow(0L)
    private val processed = MutableStateFlow(0L)
    private var dequeued = 0L

    private var dirty = false
    private var lastSyncAt = 0L
    private var conflictStreak = 0
    private var wasActive = false
    private var enableChoice: CompletableDeferred<Boolean?>? = null
    private var dismissedRemote: QueueInfo? = null

    private val _pendingRemote = MutableStateFlow<QueueInfo?>(null)
    val pendingRemote: StateFlow<QueueInfo?> = _pendingRemote.asStateFlow()

    private val _pendingEnableChoice = MutableStateFlow<QueueInfo?>(null)
    val pendingEnableChoice: StateFlow<QueueInfo?> = _pendingEnableChoice.asStateFlow()

    private val _pendingConflict = MutableStateFlow<QueueInfo?>(null)
    val pendingConflict: StateFlow<QueueInfo?> = _pendingConflict.asStateFlow()

    private val _devices = MutableStateFlow<List<QueueSyncDevice>>(emptyList())
    val devices: StateFlow<List<QueueSyncDevice>> = _devices.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _status = MutableStateFlow(QueueSyncStatus(0L, false, null))
    val status: StateFlow<QueueSyncStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<QueueSyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<QueueSyncEvent> = _events.asSharedFlow()

    private val _requestStatuses = MutableStateFlow<Map<PlatformUUID, ClientRequestStatus>>(emptyMap())
    val requestStatuses: StateFlow<Map<PlatformUUID, ClientRequestStatus>> = _requestStatuses.asStateFlow()

    private val _busySessionId = MutableStateFlow<PlatformUUID?>(null)
    val busySessionId: StateFlow<PlatformUUID?> = _busySessionId.asStateFlow()

    private val ownSessionId: PlatformUUID?
        get() = rpcServiceManager.sessionId?.toPlatformUUID()

    fun start() {
        if (started) return
        started = true

        scope.launch {
            playerModel.queueChanges
                .onSubscription { receivedSerial.value = playerModel.changeSerial.value }
                .collect { change ->
                    val serial = receivedSerial.value + 1
                    val syncable = accepting.value && playerModel.currentSource.value?.isEndless != true
                    if (syncable && outbound.trySend(Pending(serial, change)).isSuccess) enqueued.value++
                    receivedSerial.value = serial
                }
        }

        scope.launch {
            combine(
                Config.isQueueSyncEnabled,
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState
            ) { enabled, reachable, connection ->
                Gate(
                    enabled = enabled,
                    reachable = reachable,
                    authenticated = connection == RpcServiceManager.ConnectionState.Authenticated,
                    owner = owner()
                )
            }.distinctUntilChanged().collectLatest { gate ->
                when {
                    gate.isActive -> {
                        accepting.value = true
                        wasActive = true
                        runCatching { session(gate.owner) }.onFailure { report(it) }
                    }

                    !gate.enabled || !gate.authenticated -> {
                        accepting.value = false
                        reset(gate.authenticated && gate.reachable)
                    }

                    else -> accepting.value = true
                }
            }
        }
    }

    fun applyPendingRemote() {
        if (_pendingRemote.value == null) return
        scope.launch {
            val (info, started) = mutex.withLock {
                val info = _pendingRemote.value ?: return@launch
                _pendingRemote.value = null
                publishStatus()
                info to epoch
            }
            lane.withLock {
                if (mutex.withLock { epoch != started || syncedVersion >= info.version }) return@withLock
                val applied = runCatching { pull(info, explicit = true) }.onFailure { report(it) }.getOrNull()
                if (applied != null) return@withLock
                mutex.withLock {
                    if (epoch == started && syncedVersion < info.version && _pendingConflict.value == null) {
                        _pendingRemote.value = info
                        publishStatus()
                    }
                }
            }
        }
    }

    fun dismissPendingRemote() {
        scope.launch {
            mutex.withLock {
                dismissedRemote = _pendingRemote.value ?: return@withLock
                _pendingRemote.value = null
                publishStatus()
            }
        }
    }

    fun resolveEnableChoice(loadRemote: Boolean) {
        scope.launch { mutex.withLock { completeEnableChoice(loadRemote) } }
    }

    fun cancelEnableChoice() {
        scope.launch { mutex.withLock { completeEnableChoice(null) } }
        Config.setIsQueueSyncEnabled(false)
    }

    private fun completeEnableChoice(loadRemote: Boolean?) {
        _pendingEnableChoice.value = null
        enableChoice?.complete(loadRemote)
        enableChoice = null
    }

    fun resolveConflict(keepLocal: Boolean) {
        if (_pendingConflict.value == null) return
        scope.launch {
            val (info, started) = mutex.withLock {
                val info = _pendingConflict.value ?: return@launch
                drainOutbound()
                _pendingConflict.value = null
                conflictStreak = 0
                epoch++
                info to epoch
            }
            lane.withLock {
                if (mutex.withLock { epoch != started }) return@withLock
                runCatching {
                    if (keepLocal) fullUpload(force = true) else pull(info, explicit = true)
                }.onFailure { report(it) }
            }
        }
    }

    fun refreshDevices() {
        scope.launch {
            _isBusy.value = true
            try {
                _devices.value = queueService.getSyncDevices()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                report(e)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun requestQueueFrom(sessionId: PlatformUUID) {
        if (_busySessionId.value != null) return
        scope.launch {
            _busySessionId.value = sessionId
            _requestStatuses.value = _requestStatuses.value - sessionId
            try {
                val status = requestUpload(sessionId)
                _requestStatuses.value = _requestStatuses.value + (sessionId to status)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _requestStatuses.value =
                    _requestStatuses.value + (sessionId to ClientRequestStatus.UNREACHABLE)
            } finally {
                _busySessionId.value = null
                refreshDevices()
            }
        }
    }

    fun deviceName(): String = identity.deviceName()

    suspend fun ensureVersion(version: Long, timeout: Duration): Boolean {
        if (syncedVersion >= version) return true
        if (!isSessionActive()) return false
        if (_pendingEnableChoice.value != null || _pendingConflict.value != null) return false
        val reached = withTimeoutOrNull(timeout) {
            val info = runCatching { queueService.getQueueInfo() }.onFailure { report(it) }.getOrNull()
            if (info != null && info.version >= version) {
                lane.withLock {
                    val replaced = mutex.withLock { if (syncedVersion < version) hasPendingReplaced() else null }
                    if (replaced != null) {
                        runCatching {
                            if (replaced) {
                                conflictWithoutMerge(info)
                            } else {
                                pull(info, keepCurrentQueueId = currentQueueId(), explicit = true)
                            }
                        }.onFailure { report(it) }
                    }
                }
            }
            syncedVersionFlow.first { it >= version }
            true
        }
        return reached == true
    }

    suspend fun awaitFlushed(timeout: Duration): Long? {
        if (!isSessionActive()) return null
        val serial = playerModel.changeSerial.value
        return withTimeoutOrNull(timeout) {
            receivedSerial.first { it >= serial }
            processed.first { it >= enqueued.value }
            lane.withLock { mutex.withLock { syncedVersion } }
        }
    }

    private fun isSessionActive(): Boolean = wasActive && Config.isQueueSyncEnabled.value

    private suspend fun requestUpload(sessionId: PlatformUUID): ClientRequestStatus {
        _isBusy.value = true
        return try {
            val status = queueService.requestUploadFrom(sessionId)
            if (status == ClientRequestStatus.COMPLETED) {
                val info = queueService.getQueueInfo()
                lane.withLock {
                    if (mutex.withLock { info.version > syncedVersion || _pendingRemote.value != null }) {
                        pull(info, explicit = true)
                    }
                }
            }
            status
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            report(e)
            ClientRequestStatus.UNREACHABLE
        } finally {
            _isBusy.value = false
        }
    }

    private data class Gate(
        val enabled: Boolean,
        val reachable: Boolean,
        val authenticated: Boolean,
        val owner: String?
    ) {
        val isActive: Boolean get() = enabled && reachable && authenticated
    }

    private fun owner(): String? {
        val user = rpcServiceManager.userId ?: return null
        return "${rpcServiceManager.host}:${rpcServiceManager.port}/$user"
    }

    private suspend fun session(owner: String?) = coroutineScope {
        mutex.withLock {
            loadPersistedState(owner)
            conflictStreak = 0
            if (syncedVersion == 0L) drainOutbound()
            publishStatus()
        }

        runCatching { queueService.setSyncEnabled(true, deviceName()) }.onFailure { report(it) }

        launch {
            Config.queueSyncDeviceName
                .map { deviceName() }
                .distinctUntilChanged()
                .drop(1)
                .collect { name ->
                    runCatching { queueService.setSyncEnabled(true, name) }.onFailure { report(it) }
                }
        }
        launch { observeQueueLoop() }
        launch {
            presenceService.requests
                .filterIsInstance<ClientRequest.UploadQueue>()
                .collect { request -> onClientRequest(request) }
        }
        launch {
            runCatching { reconcile() }.onFailure { report(it) }
            outboundWorker()
        }
    }

    private fun loadPersistedState(owner: String?) {
        val persistedOwner = settings.getOrNull(SettingKey.QueueSyncOwner)
        if (persistedOwner == null || persistedOwner == owner) {
            syncedVersion = settings.get(SettingKey.QueueSyncVersion, 0L)
            dirty = settings.get(SettingKey.QueueSyncDirty, false)
            lastSyncAt = settings.get(SettingKey.QueueSyncLastAt, 0L)
        } else {
            syncedVersion = 0L
            dirty = false
            lastSyncAt = 0L
            settings.put(SettingKey.QueueSyncVersion, 0L)
            settings.put(SettingKey.QueueSyncDirty, false)
            settings.put(SettingKey.QueueSyncLastAt, 0L)
        }
        settings.put(SettingKey.QueueSyncOwner, owner)
    }

    private fun stagePending() {
        while (true) {
            val result = outbound.tryReceive()
            if (!result.isSuccess) break
            val pending = result.getOrThrow()
            if (pending.serial > coveredSerial.value) held.addLast(pending.change)
            dequeued++
        }
    }

    private fun syncProcessed() {
        processed.value = dequeued - held.size
    }

    private fun drainOutbound(): List<PlayerModel.QueueChange> {
        stagePending()
        val drained = held.toList()
        held.clear()
        unacknowledged = null
        coveredSerial.value = playerModel.changeSerial.value
        syncProcessed()
        return drained
    }

    private fun hasPendingReplaced(): Boolean {
        stagePending()
        return held.any { it is PlayerModel.QueueChange.Replaced }
    }

    private suspend fun reconcile() {
        val info = queueService.getQueueInfo()
        val localEmpty = playerModel.queue.value.isEmpty()

        if (syncedVersion == 0L && info.version > 0L && info.total > 0 && !localEmpty) {
            val decision = CompletableDeferred<Boolean?>()
            mutex.withLock {
                enableChoice = decision
                _pendingEnableChoice.value = info
            }
            val loadRemote = try {
                decision.await()
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock { if (enableChoice === decision) completeEnableChoice(null) }
                }
            } ?: return
            lane.withLock {
                if (loadRemote) pull(info, explicit = true) else fullUpload(force = true)
            }
            return
        }

        val cleared = info.modifiedBySessionId == null && info.total == 0
        lane.withLock {
            val (behind, holding, wasDirty) = mutex.withLock {
                stagePending()
                Triple(info.version > syncedVersion, held.isNotEmpty(), dirty)
            }
            when {
                behind && cleared -> onRemote(info)

                holding -> Unit

                wasDirty -> if (behind) {
                    conflictWithoutMerge(info)
                } else {
                    fullUpload(force = false)?.let { onReplacedConflict(it) }
                }

                behind -> onRemote(info)

                info.version == 0L && !localEmpty -> fullUpload(force = false)
            }
        }
    }

    private suspend fun observeQueueLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching {
                queueService.observeQueue().collect { info -> onQueueInfo(info) }
            }.onFailure {
                if (it is CancellationException) throw it
                report(it)
            }
            delay(STREAM_RETRY_DELAY)
        }
    }

    private suspend fun onQueueInfo(info: QueueInfo) {
        val own = ownSessionId
        if (own != null && info.modifiedBySessionId == own) {
            lane.withLock {
                mutex.withLock {
                    if (info.version > syncedVersion) {
                        val head = held.firstOrNull()
                        if (info.version == syncedVersion + 1 && head != null && head === unacknowledged) {
                            held.removeFirst()
                            syncProcessed()
                        }
                        unacknowledged = null
                        syncedVersion = info.version
                        markSynced()
                    }
                }
            }
        } else if (info.version > syncedVersion) {
            lane.withLock {
                if (mutex.withLock { info.version > syncedVersion }) {
                    runCatching { onRemote(info) }.onFailure { report(it) }
                }
            }
        }
    }

    private suspend fun onClientRequest(request: ClientRequest.UploadQueue) {
        val endless = playerModel.currentSource.value?.isEndless == true
        if (endless || playerModel.queue.value.isEmpty()) {
            runCatching { clientRequestService.complete(request.id, ClientRequestStatus.REJECTED) }
            return
        }
        runCatching { lane.withLock { fullUpload(force = true, requestId = request.id) } }
            .onFailure {
                report(it)
                runCatching {
                    clientRequestService.complete(request.id, ClientRequestStatus.REJECTED)
                }
            }
    }

    private sealed class Step {
        data class Idle(val seen: Long) : Step()
        data class Debounce(val head: PlayerModel.QueueChange, val seen: Long) : Step()
        data class Push(val head: PlayerModel.QueueChange, val seen: Long) : Step()
        data class Pushed(val ok: Boolean, val seen: Long) : Step()
    }

    private suspend fun outboundWorker() {
        var expired: PlayerModel.QueueChange? = null
        while (currentCoroutineContext().isActive) {
            awaitDecisions()
            val base = syncedVersion
            val step = lane.withLock {
                val planned = mutex.withLock plan@{
                    if (_pendingEnableChoice.value != null || _pendingConflict.value != null) {
                        return@plan Step.Idle(enqueued.value)
                    }
                    stagePending()
                    while (held.size > 1 &&
                        held[0] is PlayerModel.QueueChange.CurrentChanged &&
                        held[1] is PlayerModel.QueueChange.CurrentChanged
                    ) {
                        held.removeFirst()
                    }
                    syncProcessed()
                    val head = held.firstOrNull()
                    when {
                        head == null -> Step.Idle(enqueued.value)
                        head is PlayerModel.QueueChange.CurrentChanged && held.size == 1 && head !== expired ->
                            Step.Debounce(head, enqueued.value)
                        else -> Step.Push(head, enqueued.value)
                    }
                }
                if (planned is Step.Push) Step.Pushed(pushHead(planned.head), planned.seen) else planned
            }
            when (step) {
                is Step.Idle -> enqueued.first { it > step.seen }
                is Step.Debounce -> {
                    val arrived = withTimeoutOrNull(CURRENT_DEBOUNCE) { enqueued.first { it > step.seen } }
                    if (arrived == null) expired = step.head
                }

                is Step.Push -> Unit

                is Step.Pushed -> if (!step.ok) {
                    combine(syncedVersionFlow, enqueued) { version, count -> version != base || count > step.seen }
                        .first { it }
                }
            }
        }
    }

    private suspend fun pushHead(change: PlayerModel.QueueChange): Boolean {
        val (base, started) = mutex.withLock {
            unacknowledged = null
            syncedVersion to epoch
        }
        try {
            push(change, base, started)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock { if (epoch == started) restoreHead(change) }
            }
            throw e
        } catch (e: ReadFailure) {
            report(e.cause ?: e)
            mutex.withLock { if (epoch == started) restoreHead(change) }
            return false
        } catch (e: Throwable) {
            return mutex.withLock {
                if (epoch != started) return@withLock false
                markDirty(e)
                restoreHead(change)
                if (e.isTransportFailure() || change is PlayerModel.QueueChange.Replaced) {
                    unacknowledged = change
                    return@withLock false
                }
                logger.warning(QUEUE_SYNC_TAG, "Replacing a queue change the server rejected by a full upload", change)
                held.removeFirst()
                held.addFirst(PlayerModel.QueueChange.Replaced)
                syncProcessed()
                true
            }
        }
        mutex.withLock {
            if (held.firstOrNull() === change) held.removeFirst()
            syncProcessed()
        }
        return true
    }

    private fun restoreHead(change: PlayerModel.QueueChange) {
        if (held.firstOrNull() !== change) held.addFirst(change)
        syncProcessed()
    }

    private suspend fun awaitDecisions() {
        combine(_pendingEnableChoice, _pendingConflict) { enable, conflict ->
            enable == null && conflict == null
        }.first { it }
    }

    private suspend fun push(change: PlayerModel.QueueChange, base: Long, started: Long) {
        val result = when (change) {
            is PlayerModel.QueueChange.Replaced -> {
                fullUpload(force = false)?.let { info -> translatingReadFailures { onReplacedConflict(info) } }
                null
            }

            is PlayerModel.QueueChange.Inserted -> queueService.insert(
                base,
                change.index,
                change.entries.map(::toItem)
            )

            is PlayerModel.QueueChange.Removed ->
                queueService.remove(base, change.queueIds)

            is PlayerModel.QueueChange.Moved ->
                queueService.move(base, change.queueId, change.toIndex)

            is PlayerModel.QueueChange.CurrentChanged ->
                queueService.setCurrentIndex(base, change.index)

            is PlayerModel.QueueChange.ModesChanged ->
                queueService.setModes(base, change.shuffle, change.repeat)
        }

        when (result) {
            null -> Unit
            is QueueWriteResult.Ok -> mutex.withLock {
                if (epoch != started) return
                syncedVersion = result.info.version
                conflictStreak = 0
                markSynced()
            }

            is QueueWriteResult.Conflict -> {
                val info = result.info
                val followup = mutex.withLock {
                    if (epoch != started) return
                    conflictStreak++
                    val kept = dismissedRemote
                    when {
                        isOwnLostWrite(info) -> {
                            adoptOwnLostWrite(info)
                            Followup.None
                        }

                        hasPendingReplaced() -> Followup.Ask

                        presenceService.isControlled.value -> {
                            if (kept != null) logControllerWins(info)
                            Followup.Rebase
                        }

                        kept != null || conflictStreak >= MAX_CONFLICTS -> {
                            _pendingConflict.value = info
                            Followup.None
                        }

                        else -> Followup.Rebase
                    }
                }
                translatingReadFailures {
                    when (followup) {
                        Followup.Ask -> conflictWithoutMerge(info)
                        Followup.Rebase -> rebase(info, change)
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun translatingReadFailures(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ReadFailure(e)
        }
    }

    private fun adoptOwnLostWrite(info: QueueInfo) {
        syncedVersion = info.version
        conflictStreak = 0
        markSynced()
    }

    private suspend fun onReplacedConflict(info: QueueInfo) {
        val adopted = mutex.withLock {
            val own = isOwnLostWrite(info)
            if (own) adoptOwnLostWrite(info)
            own
        }
        if (!adopted) conflictWithoutMerge(info)
    }

    private suspend fun conflictWithoutMerge(info: QueueInfo) {
        val asked = mutex.withLock {
            if (presenceService.isControlled.value) {
                drainOutbound()
                false
            } else {
                _pendingConflict.value = info
                true
            }
        }
        if (asked) return
        logger.warning(
            QUEUE_SYNC_TAG,
            "Dropping the local queue for version ${info.version} of ${info.modifiedByDeviceName}: no merge is possible while remote-controlled"
        )
        pull(info, keepCurrentQueueId = currentQueueId())
    }

    private fun logControllerWins(info: QueueInfo) {
        logger.warning(
            QUEUE_SYNC_TAG,
            "Dropping the kept local queue for version ${info.version} of ${info.modifiedByDeviceName}: the controller wins while remote-controlled"
        )
    }

    private fun isOwnLostWrite(info: QueueInfo): Boolean {
        val own = ownSessionId ?: return false
        return info.modifiedBySessionId == own && info.version == syncedVersion + 1
    }

    private fun currentQueueId(): Long? = playerModel.queue.value
        .getOrNull(playerModel.currentIndex.value)
        ?.queueId

    private fun toItem(entry: QueueEntry): QueueItem = QueueItem(
        songId = when (entry) {
            is QueueEntry.FromSource -> entry.songId
            is QueueEntry.Explicit -> entry.song.id
        },
        queueId = entry.queueId,
        position = 0,
        shuffledPosition = null,
        userAdded = entry is QueueEntry.Explicit
    )

    private suspend fun pullOrAsk(info: QueueInfo, keepCurrentQueueId: Long? = null): Boolean {
        if (pull(info, keepCurrentQueueId) != null) return true
        if (!mutex.withLock { hasPendingReplaced() }) return false
        conflictWithoutMerge(info)
        return true
    }

    private suspend fun rebase(info: QueueInfo, change: PlayerModel.QueueChange) {
        if (pullOrAsk(info, currentQueueId())) return
        if (presenceService.isControlled.value) {
            logger.warning(
                QUEUE_SYNC_TAG,
                "Dropping a local queue change: the server queue kept moving while remote-controlled",
                change
            )
        } else {
            mutex.withLock { _pendingConflict.value = info }
        }
    }

    private fun replay(changes: List<PlayerModel.QueueChange>, snapshot: PlayerModel.QueueSnapshot) {
        val taken = snapshot.original.mapTo(mutableSetOf()) { it.queueId }
        changes.forEach { change ->
            when (change) {
                is PlayerModel.QueueChange.Replaced, is PlayerModel.QueueChange.CurrentChanged -> Unit

                is PlayerModel.QueueChange.Inserted -> {
                    val fresh = change.entries.filter { it.queueId !in taken }
                    if (fresh.isNotEmpty()) {
                        playerModel.insertEntries(change.anchor, playerModel.reassignQueueIds(fresh, taken))
                    }
                }

                is PlayerModel.QueueChange.Removed ->
                    playerModel.removeByQueueIds(change.queueIds)

                is PlayerModel.QueueChange.Moved ->
                    playerModel.moveByQueueId(change.queueId, change.toIndex)

                is PlayerModel.QueueChange.ModesChanged -> playerModel.setRepeatMode(change.repeat)
            }
        }
        val latest = changes.lastOrNull { it is PlayerModel.QueueChange.CurrentChanged }
            as? PlayerModel.QueueChange.CurrentChanged ?: return
        val queueId = latest.queueId ?: return
        if (queueId == currentQueueId()) playerModel.skipToQueueId(queueId)
    }

    private suspend fun onRemote(info: QueueInfo) {
        val followup = mutex.withLock {
            if (_pendingEnableChoice.value != null || _pendingConflict.value != null) return

            if (info.modifiedBySessionId == null && info.total == 0) {
                if (playerModel.queue.value.isNotEmpty()) return@withLock Followup.Upload
                syncedVersion = info.version
                markSynced()
                return
            }

            if (hasPendingReplaced()) return@withLock Followup.Ask

            if (dismissedRemote != null) {
                if (!presenceService.isControlled.value) {
                    _pendingRemote.value = info
                    publishStatus()
                    return
                }
                logControllerWins(info)
            }

            when {
                !playerModel.isPlaying.value || playerModel.queue.value.isEmpty() -> Followup.Pull
                presenceService.isControlled.value -> Followup.PullKeepingCurrent
                else -> {
                    _pendingRemote.value = info
                    publishStatus()
                    Followup.None
                }
            }
        }
        when (followup) {
            Followup.Upload -> fullUpload(force = true)
            Followup.Ask -> conflictWithoutMerge(info)
            Followup.Pull -> pullOrAsk(info)
            Followup.PullKeepingCurrent -> pullOrAsk(info, currentQueueId())
            else -> Unit
        }
    }

    private suspend fun pull(
        info: QueueInfo,
        keepCurrentQueueId: Long? = null,
        explicit: Boolean = false
    ): PlayerModel.QueueSnapshot? {
        val started = mutex.withLock { epoch }
        var current = info
        repeat(PULL_ATTEMPTS) {
            val items = mutableListOf<QueueItem>()
            val includeSongs = current.total <= SONG_INCLUDE_LIMIT
            var page = 0
            while (true) {
                val response = queueService.getQueue(page, PAGE_SIZE, includeSongs)
                items.addAll(response.data)
                if (!response.hasNextPage) break
                page++
            }

            val after = queueService.getQueueInfo()
            if (after.version != current.version) {
                current = after
                return@repeat
            }

            val snapshot = buildSnapshot(current, items)
            val replayed = withContext(dispatchers.main) {
                if (playerModel.changeSerial.value > receivedSerial.value) {
                    withTimeoutOrNull(CURRENT_DEBOUNCE) {
                        receivedSerial.first { it >= playerModel.changeSerial.value }
                    }
                }
                mutex.withLock {
                    if (epoch != started) return@withContext null
                    val pending = drainOutbound()
                    if (!explicit && pending.any { it is PlayerModel.QueueChange.Replaced }) {
                        held.addAll(0, pending)
                        syncProcessed()
                        return@withContext null
                    }
                    playerModel.applyRemoteQueue(snapshot, keepCurrentQueueId)
                    syncedVersion = current.version
                    markSynced()
                    replay(pending, snapshot)
                    pending
                }
            } ?: return null
            runCatching { queueService.ackSynced(current.version) }.onFailure { report(it) }
            if (replayed.any { it !is PlayerModel.QueueChange.CurrentChanged }) {
                _events.tryEmit(QueueSyncEvent.Merged(current.modifiedByDeviceName))
            }
            return snapshot
        }
        return null
    }

    private suspend fun buildSnapshot(
        info: QueueInfo,
        items: List<QueueItem>
    ): PlayerModel.QueueSnapshot {
        val songs = items.mapNotNull { it.song }.distinctBy { it.id }
        if (songs.isNotEmpty()) songCache.putAll(songs)

        val original = items.sortedBy { it.position }
        val active = if (info.isShuffled) {
            items.sortedBy { it.shuffledPosition ?: Int.MAX_VALUE }
        } else {
            original
        }
        return PlayerModel.QueueSnapshot(
            original = original.map(::toEntry),
            active = active.map(::toEntry),
            currentIndex = info.currentIndex,
            shuffle = info.isShuffled,
            repeat = info.repeatMode,
            sourceId = info.sourceId
        )
    }

    private fun toEntry(item: QueueItem): QueueEntry {
        val song = item.song
        return if (item.userAdded && song != null) {
            QueueEntry.Explicit(song, item.queueId)
        } else {
            QueueEntry.FromSource(item.songId, item.queueId)
        }
    }

    private suspend fun fullUpload(force: Boolean, requestId: PlatformUUID? = null): QueueInfo? {
        val (snapshot, base, started) = withContext(dispatchers.main) {
            mutex.withLock {
                drainOutbound()
                Triple(playerModel.snapshot(), syncedVersion, epoch)
            }
        }
        if (playerModel.currentSource.value?.isEndless == true) return null

        var uploadId: PlatformUUID? = null
        try {
            val start = queueService.beginUpload(base, force)
            if (start is QueueUploadStart.Conflict) return start.info.takeIf { mutex.withLock { epoch == started } }
            val id = (start as QueueUploadStart.Started).uploadId
            uploadId = id

            val activeIndex = snapshot.active.withIndex()
                .associate { (index, entry) -> entry.queueId to index }
            snapshot.original
                .mapIndexed { index, entry ->
                    toItem(entry).copy(
                        position = index,
                        shuffledPosition = if (snapshot.shuffle) activeIndex[entry.queueId] else null
                    )
                }
                .chunked(UPLOAD_CHUNK)
                .forEach { queueService.uploadPage(id, it) }

            val result = queueService.commitUpload(
                id,
                QueueMeta(
                    currentIndex = snapshot.currentIndex,
                    isShuffled = snapshot.shuffle,
                    repeatMode = snapshot.repeat,
                    sourceId = snapshot.sourceId
                ),
                requestId
            )
            when (result) {
                is QueueWriteResult.Ok -> mutex.withLock {
                    if (epoch != started) return null
                    syncedVersion = result.info.version
                    conflictStreak = 0
                    markSynced()
                }

                is QueueWriteResult.Conflict -> return result.info.takeIf { mutex.withLock { epoch == started } }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock { if (epoch == started) markDirty(e) }
            }
            throw e
        } catch (e: Throwable) {
            uploadId?.let { id -> runCatching { queueService.cancelUpload(id) } }
            mutex.withLock { if (epoch == started) markDirty(e) }
            throw e
        }
        return null
    }

    private suspend fun reset(notifyServer: Boolean) {
        mutex.withLock {
            drainOutbound()
            if (!wasActive) return
            wasActive = false
            epoch++

            if (notifyServer) {
                scope.launch {
                    runCatching { queueService.setSyncEnabled(false, deviceName()) }.onFailure { report(it) }
                }
            }

            enableChoice?.complete(null)
            enableChoice = null
            dismissedRemote = null
            _pendingRemote.value = null
            _pendingEnableChoice.value = null
            _pendingConflict.value = null
            _devices.value = emptyList()
            syncedVersion = 0L
            dirty = false
            conflictStreak = 0

            settings.put(SettingKey.QueueSyncVersion, 0L)
            settings.put(SettingKey.QueueSyncDirty, false)
            publishStatus()
        }
    }

    private fun markSynced() {
        dirty = false
        dismissedRemote = null
        _pendingRemote.value = null
        lastSyncAt = currentTimeMillis()
        settings.put(SettingKey.QueueSyncVersion, syncedVersion)
        settings.put(SettingKey.QueueSyncDirty, false)
        settings.put(SettingKey.QueueSyncLastAt, lastSyncAt)
        publishStatus()
    }

    private fun markDirty(error: Throwable) {
        report(error)
        dirty = true
        settings.put(SettingKey.QueueSyncDirty, true)
        publishStatus()
    }

    private fun publishStatus() {
        _status.value = QueueSyncStatus(
            lastSyncAt = lastSyncAt,
            uploadPending = dirty,
            behindDevice = _pendingRemote.value?.modifiedByDeviceName
        )
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(QUEUE_SYNC_TAG, error.message ?: "Queue sync failed", error)
    }
}
