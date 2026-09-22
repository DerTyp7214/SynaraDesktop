package dev.dertyp.synara.player

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IClientRequestService
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val REMOTE_CONTROL_TAG = LogTag("remote-control")

class RemoteControlService(
    private val presenceService: PresenceService,
    private val clientRequestService: IClientRequestService,
    private val remoteControlService: IRemoteControlService,
    private val queueSyncService: QueueSyncService,
    private val playerModel: PlayerModel,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) {
    companion object {
        private val COALESCE_WINDOW = 150.milliseconds
        private val HEARTBEAT_INTERVAL = 10.seconds
        private val INITIAL_REPORT_DELAY = 1.seconds
        private val ENSURE_VERSION_TIMEOUT = 8.seconds
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var started = false

    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var heartbeatJob: Job? = null

    fun start() {
        if (started) return
        started = true

        scope.launch {
            presenceService.requests
                .filterIsInstance<ClientRequest.ControlPlayback>()
                .collect { request -> handle(request) }
        }

        scope.launch {
            while (isActive) {
                triggers.receive()
                delay(COALESCE_WINDOW)
                triggers.tryReceive()
                reportNow()
            }
        }

        scope.launch { reportLoop() }
    }

    private suspend fun handle(request: ClientRequest.ControlPlayback) {
        presenceService.markControlled()
        val applied = try {
            apply(request.command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(e)
            false
        }
        if (applied) reportNow()
        val status = if (applied) ClientRequestStatus.COMPLETED else ClientRequestStatus.REJECTED
        runCatching { clientRequestService.complete(request.id, status) }.onFailure { report(it) }
    }

    private suspend fun apply(command: PlaybackCommand): Boolean = when (command) {
        is PlaybackCommand.Play -> onMain {
            if (!hasCurrent()) return@onMain false
            playerModel.play()
            true
        }

        is PlaybackCommand.Pause -> onMain {
            if (!hasCurrent()) return@onMain false
            playerModel.pause()
            true
        }

        is PlaybackCommand.TogglePlayPause -> onMain {
            if (!hasCurrent()) return@onMain false
            playerModel.togglePlayPause()
            true
        }

        is PlaybackCommand.Next -> onMain {
            if (!hasCurrent()) return@onMain false
            playerModel.skipNext()
            true
        }

        is PlaybackCommand.Previous -> onMain {
            if (!hasCurrent()) return@onMain false
            playerModel.skipPrevious()
            true
        }

        is PlaybackCommand.SeekTo -> onMain {
            if (!hasCurrent()) return@onMain false
            val duration = playerModel.duration.value
            val target = if (duration > 0L) {
                command.positionMs.coerceIn(0L, duration)
            } else {
                command.positionMs.coerceAtLeast(0L)
            }
            playerModel.seekTo(target)
            true
        }

        is PlaybackCommand.SetShuffle -> onMain {
            if (playerModel.currentSource.value?.isEndless == true) return@onMain false
            playerModel.setShuffleMode(command.enabled)
            true
        }

        is PlaybackCommand.SetRepeat -> onMain {
            playerModel.setRepeatMode(command.mode)
            true
        }

        is PlaybackCommand.SetVolume -> onMain {
            playerModel.setVolume(command.volume.coerceIn(0f, 1f))
            true
        }

        is PlaybackCommand.PlayQueueItem -> {
            if (!queueSyncService.ensureVersion(command.queueVersion, ENSURE_VERSION_TIMEOUT)) {
                false
            } else {
                onMain { playerModel.skipToQueueId(command.queueId) }
            }
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(dispatchers.main) { block() }

    private fun hasCurrent(): Boolean = playerModel.currentSong.value != null

    private suspend fun reportLoop() {
        combine(Config.isRemoteControlEnabled, presenceService.isConnected) { enabled, connected ->
            enabled && connected
        }.distinctUntilChanged().collectLatest { active ->
            if (!active) {
                updateHeartbeat(false)
                return@collectLatest
            }
            updateHeartbeat(playerModel.isPlaying.value)
            coroutineScope {
                launch {
                    delay(INITIAL_REPORT_DELAY)
                    triggerReport()
                }
                launch {
                    playerModel.isPlaying.drop(1).collect { playing ->
                        updateHeartbeat(playing)
                        triggerReport()
                    }
                }
                launch { playerModel.currentSong.drop(1).collect { triggerReport() } }
                launch { playerModel.currentIndex.drop(1).collect { triggerReport() } }
                launch { playerModel.shuffleMode.drop(1).collect { triggerReport() } }
                launch { playerModel.repeatMode.drop(1).collect { triggerReport() } }
                launch { playerModel.volume.drop(1).collect { triggerReport() } }
                launch { playerModel.seekEvents.collect { triggerReport() } }
                launch { queueSyncService.syncedVersionFlow.drop(1).collect { triggerReport() } }
            }
        }
    }

    private fun triggerReport() {
        triggers.trySend(Unit)
    }

    private fun updateHeartbeat(playing: Boolean) {
        if (!playing || !Config.isRemoteControlEnabled.value) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                triggerReport()
            }
        }
    }

    private suspend fun reportNow() {
        if (!Config.isRemoteControlEnabled.value) return
        if (!presenceService.isConnected.value) return

        val entry = playerModel.queue.value.getOrNull(playerModel.currentIndex.value)
        val status = RemotePlaybackStatus(
            songId = playerModel.currentSong.value?.id,
            isPlaying = playerModel.isPlaying.value,
            positionMs = playerModel.currentPosition.value,
            durationMs = playerModel.duration.value.takeIf { it > 0L },
            shuffleMode = playerModel.shuffleMode.value,
            repeatMode = playerModel.repeatMode.value,
            volume = playerModel.volume.value,
            currentQueueId = entry?.queueId,
            queueVersion = queueSyncService.syncedVersionFlow.value.takeIf { it > 0L }
        )
        runCatching { remoteControlService.reportStatus(status) }.onFailure { report(it) }
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(REMOTE_CONTROL_TAG, error.message ?: "Remote control failed", error)
    }
}
