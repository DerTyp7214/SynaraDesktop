package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.ServerClock
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val REMOTE_PLAYBACK_TAG = LogTag("remote-playback")

class RemotePlaybackController(
    private val presenceService: PresenceService,
    private val remoteControlService: IRemoteControlService,
    private val queueSyncService: QueueSyncService,
    private val playerModel: PlayerModel,
    private val songService: ISongService,
    private val songCache: SongCache,
    private val serverClock: ServerClock,
    private val snackbarManager: SnackbarManager,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) : PlaybackSurface {
    companion object {
        private val WATCH_RETRY_DELAY = 5.seconds
        private val POSITION_TICK = 250.milliseconds
        private val VOLUME_THROTTLE = 200.milliseconds
        private val FLUSH_TIMEOUT = 8.seconds
        private const val MAX_WATCH_FAILURES = 3
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val _target = MutableStateFlow<OnlineDevice?>(null)
    val target: StateFlow<OnlineDevice?> = _target.asStateFlow()

    val controllableDevices: StateFlow<List<OnlineDevice>> = presenceService.onlineDevices
        .map { devices ->
            devices.filter { !it.isCurrent && ClientCapability.REMOTE_CONTROL in it.capabilities }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<UserSong?>(null)
    override val currentSong: StateFlow<UserSong?> = _currentSong.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    override val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    override val supportsVolume: Boolean
        get() = _target.value?.capabilities?.contains(ClientCapability.REMOTE_VOLUME) == true

    private val volumeCommands = Channel<Float>(Channel.CONFLATED)
    private var watchJob: Job? = null
    private var tickerJob: Job? = null
    private var lastStatus: RemotePlaybackStatus? = null
    private var currentSongId: PlatformUUID? = null

    init {
        scope.launch {
            controllableDevices.collect { devices ->
                val selected = _target.value ?: return@collect
                val fresh = devices.firstOrNull { it.sessionId == selected.sessionId }
                if (fresh == null) deselect() else if (fresh != selected) _target.value = fresh
            }
        }

        scope.launch {
            while (isActive) {
                val value = volumeCommands.receive()
                val device = _target.value
                if (device != null) {
                    deliver(device.sessionId, PlaybackCommand.SetVolume(value.coerceIn(0f, 1f)))
                }
                delay(VOLUME_THROTTLE)
            }
        }
    }

    fun select(sessionId: PlatformUUID?) {
        val device = sessionId?.let { id -> controllableDevices.value.firstOrNull { it.sessionId == id } }
        if (device == null) {
            deselect()
            return
        }
        if (_target.value?.sessionId == device.sessionId) return

        if (!Config.isQueueSyncEnabled.value) Config.setIsQueueSyncEnabled(true)
        playerModel.pause()
        resetState()
        _target.value = device
        playerModel.remotePlayHandler = { queueId -> onRemotePlay(queueId) }
        watchJob?.cancel()
        watchJob = scope.launch { watch(device.sessionId) }
    }

    fun deselect() {
        if (_target.value == null && playerModel.remotePlayHandler == null) return
        playerModel.remotePlayHandler = null
        watchJob?.cancel()
        watchJob = null
        _target.value = null
        resetState()
    }

    override fun togglePlayPause() {
        send(PlaybackCommand.TogglePlayPause)
    }

    override fun skipNext() {
        send(PlaybackCommand.Next)
    }

    override fun skipPrevious() {
        send(PlaybackCommand.Previous)
    }

    override fun seekTo(positionMs: Long) {
        val duration = _duration.value
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        _currentPosition.value = target
        send(PlaybackCommand.SeekTo(target))
    }

    override fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _volume.value = clamped
        volumeCommands.trySend(clamped)
    }

    override fun toggleShuffle() {
        send(PlaybackCommand.SetShuffle(!_shuffleMode.value))
    }

    override fun toggleRepeat() {
        val next = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        send(PlaybackCommand.SetRepeat(next))
    }

    private fun onRemotePlay(queueId: Long) {
        val device = _target.value ?: return
        scope.launch {
            val version = queueSyncService.awaitFlushed(FLUSH_TIMEOUT)
            if (version == null) {
                notify(Res.string.queue_sync_status_timed_out)
                return@launch
            }
            deliver(device.sessionId, PlaybackCommand.PlayQueueItem(queueId, version))
        }
    }

    private fun send(command: PlaybackCommand) {
        val device = _target.value ?: return
        scope.launch { deliver(device.sessionId, command) }
    }

    private suspend fun deliver(sessionId: PlatformUUID, command: PlaybackCommand) {
        val status = try {
            remoteControlService.sendCommand(sessionId, command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(e)
            null
        }

        when (status) {
            null -> {
                notify(Res.string.queue_sync_status_unreachable)
                presenceService.refreshOnlineDevices()
                deselect()
            }

            ClientRequestStatus.COMPLETED -> Unit

            ClientRequestStatus.REJECTED -> notify(Res.string.queue_sync_status_rejected)

            ClientRequestStatus.TIMED_OUT -> notify(Res.string.queue_sync_status_timed_out)

            ClientRequestStatus.UNREACHABLE -> {
                notify(Res.string.queue_sync_status_unreachable)
                presenceService.refreshOnlineDevices()
                deselect()
            }
        }
    }

    private suspend fun watch(sessionId: PlatformUUID) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val succeeded = runCatching {
                remoteControlService.observeStatus(sessionId).collect { status -> onStatus(status) }
            }.onFailure {
                if (it is CancellationException) throw it
                report(it)
            }.isSuccess

            failures = if (succeeded) 0 else failures + 1
            presenceService.refreshOnlineDevices()
            if (failures >= MAX_WATCH_FAILURES) {
                deselect()
                return
            }
            delay(WATCH_RETRY_DELAY)
        }
    }

    private suspend fun onStatus(status: RemotePlaybackStatus) {
        lastStatus = status
        _isPlaying.value = status.isPlaying
        _duration.value = status.durationMs ?: 0L
        _shuffleMode.value = status.shuffleMode
        _repeatMode.value = status.repeatMode
        status.volume?.let { _volume.value = it }
        _currentPosition.value = projectedPosition(status)

        val songId = status.songId
        if (songId != currentSongId) {
            currentSongId = songId
            _currentSong.value = songId?.let { id ->
                songCache.get(id) ?: runCatching { songService.byId(id) }
                    .onFailure { report(it) }
                    .getOrNull()
                    ?.also { songCache.put(it) }
            }
        }

        updateTicker(status.isPlaying)
    }

    private fun projectedPosition(status: RemotePlaybackStatus): Long {
        val elapsed = if (status.isPlaying && status.reportedAt > 0L) {
            (serverClock.serverNow() - status.reportedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        val position = status.positionMs + elapsed
        val duration = status.durationMs ?: 0L
        return if (duration > 0L) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
    }

    private fun updateTicker(playing: Boolean) {
        if (!playing) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_TICK)
                val status = lastStatus ?: break
                _currentPosition.value = projectedPosition(status)
            }
        }
    }

    private fun resetState() {
        tickerJob?.cancel()
        tickerJob = null
        lastStatus = null
        currentSongId = null
        _isPlaying.value = false
        _currentSong.value = null
        _currentPosition.value = 0L
        _duration.value = 0L
        _volume.value = 1f
        _shuffleMode.value = false
        _repeatMode.value = RepeatMode.OFF
    }

    private suspend fun notify(resource: StringResource) {
        snackbarManager.showSnackbar(getString(resource))
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(REMOTE_PLAYBACK_TAG, error.message ?: "Remote playback failed", error)
    }
}
