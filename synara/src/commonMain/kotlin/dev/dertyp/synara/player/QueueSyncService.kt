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
import dev.dertyp.services.IClientRequestService
import dev.dertyp.services.IQueueService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.toPlatformUUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    private var outbound = Channel<PlayerModel.QueueChange>(Channel.UNLIMITED)

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
                    val syncable = Config.isQueueSyncEnabled.value &&
                        playerModel.currentSource.value?.isEndless != true
                    if (syncable && outbound.trySend(change).isSuccess) enqueued.value++
                    receivedSerial.value++
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
                    authenticated = connection == RpcServiceManager.ConnectionState.Authenticated
                )
            }.distinctUntilChanged().collectLatest { gate ->
                when {
                    gate.isActive -> {
                        wasActive = true
                        runCatching { session() }.onFailure { report(it) }
                    }

                    !gate.enabled || !gate.authenticated -> reset(gate.authenticated && gate.reachable)
                }
            }
        }
    }

    fun applyPendingRemote() {
        val info = _pendingRemote.value ?: return
        scope.launch { mutex.withLock { runCatching { pull(info) }.onFailure { report(it) } } }
    }

    fun dismissPendingRemote() {
        _pendingRemote.value = null
        publishStatus()
    }

    fun resolveEnableChoice(loadRemote: Boolean) {
        _pendingEnableChoice.value = null
        enableChoice?.complete(loadRemote)
        enableChoice = null
    }

    fun cancelEnableChoice() {
        _pendingEnableChoice.value = null
        enableChoice?.complete(null)
        enableChoice = null
        Config.setIsQueueSyncEnabled(false)
    }

    fun resolveConflict(keepLocal: Boolean) {
        val info = _pendingConflict.value ?: return
        _pendingConflict.value = null
        conflictStreak = 0
        scope.launch {
            mutex.withLock {
                runCatching {
                    if (keepLocal) fullUpload(force = true) else pull(info)
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
        val reached = withTimeoutOrNull(timeout) {
            val info = runCatching { queueService.getQueueInfo() }.onFailure { report(it) }.getOrNull()
            if (info != null && info.version >= version) {
                mutex.withLock {
                    if (syncedVersion < version) {
                        val keepId = playerModel.queue.value
                            .getOrNull(playerModel.currentIndex.value)
                            ?.queueId
                        runCatching { pull(info, keepCurrentQueueId = keepId) }.onFailure { report(it) }
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
            mutex.withLock { syncedVersion }
        }
    }

    private fun isSessionActive(): Boolean = wasActive && Config.isQueueSyncEnabled.value

    private suspend fun requestUpload(sessionId: PlatformUUID): ClientRequestStatus {
        _isBusy.value = true
        return try {
            val status = queueService.requestUploadFrom(sessionId)
            if (status == ClientRequestStatus.COMPLETED) {
                val info = queueService.getQueueInfo()
                mutex.withLock {
                    if (info.version > syncedVersion || _pendingRemote.value != null) pull(info)
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
        val authenticated: Boolean
    ) {
        val isActive: Boolean get() = enabled && reachable && authenticated
    }

    private suspend fun session() = coroutineScope {
        syncedVersion = settings.get(SettingKey.QueueSyncVersion, 0L)
        dirty = settings.get(SettingKey.QueueSyncDirty, false)
        lastSyncAt = settings.get(SettingKey.QueueSyncLastAt, 0L)
        conflictStreak = 0
        publishStatus()

        if (discardOutbound() > 0 && playerModel.queue.value.isNotEmpty()) dirty = true

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
            runCatching { reconcile() }.onFailure { markDirty(it) }
            outboundWorker()
        }
    }

    private fun discardOutbound(): Int {
        val stale = outbound
        outbound = Channel(Channel.UNLIMITED)
        stale.close()
        var count = 0
        while (stale.tryReceive().isSuccess) count++
        dequeued = enqueued.value
        processed.value = enqueued.value
        return count
    }

    private suspend fun reconcile() {
        val info = queueService.getQueueInfo()
        val localEmpty = playerModel.queue.value.isEmpty()

        when {
            syncedVersion == 0L && info.version > 0L && info.total > 0 && !localEmpty -> {
                val decision = CompletableDeferred<Boolean?>()
                enableChoice = decision
                _pendingEnableChoice.value = info
                val loadRemote = decision.await() ?: return
                mutex.withLock {
                    if (loadRemote) pull(info) else fullUpload(force = true)
                }
            }

            dirty -> mutex.withLock { fullUpload(force = true) }

            info.version > syncedVersion -> mutex.withLock { onRemote(info) }

            info.version == 0L && !localEmpty -> mutex.withLock { fullUpload(force = false) }
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
            if (info.version > syncedVersion) {
                syncedVersion = info.version
                markSynced()
            }
        } else if (info.version > syncedVersion) {
            mutex.withLock {
                if (info.version > syncedVersion) {
                    runCatching { onRemote(info) }.onFailure { markDirty(it) }
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
        runCatching { mutex.withLock { fullUpload(force = true, requestId = request.id) } }
            .onFailure {
                report(it)
                runCatching {
                    clientRequestService.complete(request.id, ClientRequestStatus.REJECTED)
                }
            }
    }

    private suspend fun outboundWorker() {
        var carried: PlayerModel.QueueChange? = null
        while (currentCoroutineContext().isActive) {
            val source = outbound
            var change = carried ?: receiveOutbound()
            carried = null

            if (change is PlayerModel.QueueChange.CurrentChanged) {
                while (true) {
                    val next = withTimeoutOrNull(CURRENT_DEBOUNCE) { receiveOutbound() } ?: break
                    if (next is PlayerModel.QueueChange.CurrentChanged) {
                        change = next
                    } else {
                        carried = next
                        break
                    }
                }
            }

            awaitDecisions()
            mutex.withLock { push(change) }
            val pushedThrough = dequeued - if (carried != null) 1L else 0L
            processed.value = maxOf(processed.value, pushedThrough)
            if (outbound !== source) carried = null
        }
    }

    private suspend fun receiveOutbound(): PlayerModel.QueueChange {
        while (true) {
            val result = outbound.receiveCatching()
            if (result.isSuccess) {
                dequeued++
                return result.getOrThrow()
            }
        }
    }

    private suspend fun awaitDecisions() {
        combine(_pendingEnableChoice, _pendingConflict) { enable, conflict ->
            enable == null && conflict == null
        }.first { it }
    }

    private suspend fun push(change: PlayerModel.QueueChange) {
        try {
            val result = when {
                dirty -> {
                    fullUpload(force = true)
                    null
                }

                change is PlayerModel.QueueChange.Replaced -> {
                    val conflict = fullUpload(force = false)
                    if (conflict != null) fullUpload(force = true)
                    null
                }

                change is PlayerModel.QueueChange.Inserted -> queueService.insert(
                    syncedVersion,
                    change.index,
                    change.entries.map(::toItem)
                )

                change is PlayerModel.QueueChange.Removed ->
                    queueService.remove(syncedVersion, change.queueIds)

                change is PlayerModel.QueueChange.Moved ->
                    queueService.move(syncedVersion, change.queueId, change.toIndex)

                change is PlayerModel.QueueChange.CurrentChanged ->
                    queueService.setCurrentIndex(syncedVersion, change.index)

                change is PlayerModel.QueueChange.ModesChanged ->
                    queueService.setModes(syncedVersion, change.shuffle, change.repeat)

                else -> null
            }

            when (result) {
                null -> Unit
                is QueueWriteResult.Ok -> {
                    syncedVersion = result.info.version
                    conflictStreak = 0
                    markSynced()
                }

                is QueueWriteResult.Conflict -> {
                    conflictStreak++
                    if (conflictStreak >= MAX_CONFLICTS && !presenceService.isControlled.value) {
                        _pendingConflict.value = result.info
                    } else {
                        rebase(result.info, change)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markDirty(e)
        }
    }

    private fun toItem(entry: QueueEntry): QueueItem = QueueItem(
        songId = when (entry) {
            is QueueEntry.FromSource -> entry.songId
            is QueueEntry.Explicit -> entry.song.id
        },
        queueId = entry.queueId,
        position = 0,
        shuffledPosition = null,
        explicit = entry is QueueEntry.Explicit
    )

    private suspend fun rebase(info: QueueInfo, change: PlayerModel.QueueChange) {
        val keepId = playerModel.queue.value
            .getOrNull(playerModel.currentIndex.value)
            ?.queueId
        val snapshot = pull(info, keepCurrentQueueId = keepId) ?: return
        _events.tryEmit(QueueSyncEvent.Merged(info.modifiedByDeviceName))

        withContext(dispatchers.main) {
            when (change) {
                is PlayerModel.QueueChange.Replaced -> Unit

                is PlayerModel.QueueChange.Inserted -> {
                    val taken = snapshot.original.mapTo(mutableSetOf()) { it.queueId }
                    val entries = playerModel.reassignQueueIds(change.entries, taken)
                    playerModel.insertEntries(change.anchor, entries)
                }

                is PlayerModel.QueueChange.Removed ->
                    playerModel.removeByQueueIds(change.queueIds)

                is PlayerModel.QueueChange.Moved ->
                    playerModel.moveByQueueId(change.queueId, change.toIndex)

                is PlayerModel.QueueChange.CurrentChanged ->
                    change.queueId?.let { playerModel.skipToQueueId(it) }

                is PlayerModel.QueueChange.ModesChanged -> {
                    playerModel.setShuffleMode(change.shuffle)
                    playerModel.setRepeatMode(change.repeat)
                }
            }
        }
    }

    private suspend fun onRemote(info: QueueInfo) {
        if (info.modifiedBySessionId == null && info.total == 0) {
            if (playerModel.queue.value.isNotEmpty()) {
                fullUpload(force = true)
            } else {
                syncedVersion = info.version
                markSynced()
            }
            return
        }

        if (!playerModel.isPlaying.value || playerModel.queue.value.isEmpty()) {
            pull(info)
        } else if (presenceService.isControlled.value) {
            val keepId = playerModel.queue.value
                .getOrNull(playerModel.currentIndex.value)
                ?.queueId
            pull(info, keepCurrentQueueId = keepId)
        } else {
            _pendingRemote.value = info
            publishStatus()
        }
    }

    private suspend fun pull(
        info: QueueInfo,
        keepCurrentQueueId: Long? = null
    ): PlayerModel.QueueSnapshot? {
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
            withContext(dispatchers.main) {
                playerModel.applyRemoteQueue(snapshot, keepCurrentQueueId)
            }
            runCatching { queueService.ackSynced(current.version) }.onFailure { report(it) }
            syncedVersion = current.version
            _pendingRemote.value = null
            markSynced()
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
        val active = if (info.shuffleMode) {
            items.sortedBy { it.shuffledPosition ?: Int.MAX_VALUE }
        } else {
            original
        }
        return PlayerModel.QueueSnapshot(
            original = original.map(::toEntry),
            active = active.map(::toEntry),
            currentIndex = info.currentIndex,
            shuffle = info.shuffleMode,
            repeat = info.repeatMode,
            sourceId = info.sourceId
        )
    }

    private fun toEntry(item: QueueItem): QueueEntry {
        val song = item.song
        return if (item.explicit && song != null) {
            QueueEntry.Explicit(song, item.queueId)
        } else {
            QueueEntry.FromSource(item.songId, item.queueId)
        }
    }

    private suspend fun fullUpload(force: Boolean, requestId: PlatformUUID? = null): QueueInfo? {
        val snapshot = withContext(dispatchers.main) {
            discardOutbound()
            playerModel.snapshot()
        }
        if (playerModel.currentSource.value?.isEndless == true) return null

        val start = queueService.beginUpload(syncedVersion, force)
        if (start is QueueUploadStart.Conflict) return start.info
        val uploadId = (start as QueueUploadStart.Started).uploadId

        try {
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
                .forEach { queueService.uploadPage(uploadId, it) }

            val result = queueService.commitUpload(
                uploadId,
                QueueMeta(snapshot.currentIndex, snapshot.shuffle, snapshot.repeat, snapshot.sourceId),
                requestId
            )
            when (result) {
                is QueueWriteResult.Ok -> {
                    syncedVersion = result.info.version
                    conflictStreak = 0
                    markSynced()
                }

                is QueueWriteResult.Conflict -> return result.info
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { queueService.cancelUpload(uploadId) }
            markDirty(e)
            throw e
        }
        return null
    }

    private fun reset(notifyServer: Boolean) {
        if (!wasActive) return
        wasActive = false

        if (notifyServer) {
            scope.launch {
                runCatching { queueService.setSyncEnabled(false, deviceName()) }.onFailure { report(it) }
            }
        }

        enableChoice?.complete(null)
        enableChoice = null
        _pendingRemote.value = null
        _pendingEnableChoice.value = null
        _pendingConflict.value = null
        _devices.value = emptyList()
        syncedVersion = 0L
        dirty = false
        conflictStreak = 0
        discardOutbound()

        settings.put(SettingKey.QueueSyncVersion, 0L)
        settings.put(SettingKey.QueueSyncDirty, false)
        publishStatus()
    }

    private fun markSynced() {
        dirty = false
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
