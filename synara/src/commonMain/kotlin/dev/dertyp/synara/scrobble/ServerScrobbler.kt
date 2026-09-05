package dev.dertyp.synara.scrobble

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.PlaybackReport
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.services.IScrobbleService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.ServerClock
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.server_scrobbler
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ServerScrobbler(
    private val scrobbleService: IScrobbleService,
    private val scrobbleQueue: ScrobbleQueue,
    private val rpcServiceManager: RpcServiceManager,
    private val playerModel: PlayerModel,
    private val serverClock: ServerClock,
    globalJson: Json
) : BaseScrobbler() {
    override val name = Res.string.server_scrobbler
    override val icon: SynaraIcons = SynaraIcons.Sync
    override val sortOrder: Int = 0

    private val json = Json(globalJson) {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val target = "server"
    private val queueUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var isProcessing = false
    private var currentSong: UserSong? = null

    private var heartbeatJob: Job? = null

    private val playbackTriggerChannel = Channel<Boolean>(Channel.CONFLATED)

    private val isEnabled get() = Config.isServerScrobblingEnabled.value

    @OptIn(FlowPreview::class)
    override fun onStart() {
        this += scope.launch {
            queueUpdateFlow
                .onStart { emit(Unit) }
                .debounce(100.milliseconds)
                .collectLatest {
                    processQueue()
                }
        }
        this += scope.launch {
            rpcServiceManager.isServerReachable
                .filter { it }
                .collectLatest {
                    serverClock.reset()
                    updateHeartbeat(playerModel.isPlaying.value)
                    queueUpdateFlow.emit(Unit)
                }
        }
        this += scope.launch {
            playerModel.isPlaying
                .drop(1)
                .collectLatest { playing ->
                    if (currentSong != null && isEnabled) triggerReport(playing)
                    updateHeartbeat(playing)
                }
        }
        this += scope.launch {
            playerModel.seekEvents.collectLatest {
                if (currentSong != null && isEnabled) {
                    triggerReport(playerModel.isPlaying.value)
                }
            }
        }
        this += scope.launch {
            while (isActive) {
                val first = playbackTriggerChannel.receive()
                delay(COALESCE_WINDOW)
                sendReport(playbackTriggerChannel.tryReceive().getOrNull() ?: first)
            }
        }
    }

    private fun triggerReport(playing: Boolean) {
        playbackTriggerChannel.trySend(playing)
    }

    private fun updateHeartbeat(playing: Boolean) {
        val shouldRun = playing && currentSong != null && isEnabled
        if (!shouldRun) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            return
        }
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                triggerReport(true)
            }
        }
    }

    private suspend fun sendReport(playing: Boolean) {
        val song = currentSong ?: return
        if (!isEnabled) return

        val report = PlaybackReport(
            songId = song.id,
            positionMs = playerModel.currentPosition.value,
            playing = playing,
            sentAt = serverClock.serverNow()
        )
        val before = currentTimeMillis()
        try {
            val serverTime = scrobbleService.reportPlayback(report)
            serverClock.record(before, serverTime, currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning(LogTag.SCROBBLER, "Failed to report playback to server", e)
        }
    }

    override suspend fun newSong(song: UserSong?) {
        currentSong = song
        if (!isEnabled) return

        val playing = playerModel.isPlaying.value
        if (song == null || !playing) {
            try {
                scrobbleService.clearNowPlaying()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning(LogTag.SCROBBLER, "Failed to clear now playing on server", e)
            }
            updateHeartbeat(false)
            return
        }

        triggerReport(true)
        updateHeartbeat(true)
    }

    override suspend fun triggered(song: UserSong, listenedAt: Long) {
        if (!isEnabled) return
        updateStatus(ScrobbleStatus.SCROBBLED)
    }

    override suspend fun listenEnded(song: UserSong, msPlayed: Long, listenedAt: Long) {
        if (!isEnabled) return
        if (msPlayed < MIN_SCROBBLE_MS) return
        val request = ScrobbleRequest(
            songId = song.id,
            listenedAt = listenedAt,
            msPlayed = msPlayed
        )
        scrobbleQueue.pushRaw(
            songId = song.id.toString(),
            payload = json.encodeToString(request),
            timestamp = listenedAt,
            target = target
        )
        queueUpdateFlow.emit(Unit)
    }

    private suspend fun processQueue() {
        if (isProcessing) return
        isProcessing = true
        try {
            for (entry in scrobbleQueue.getAllRaw(target)) {
                try {
                    val request = json.decodeFromString<ScrobbleRequest>(entry.payload)
                    scrobbleService.listened(request.copy(listenedAt = entry.timestamp))
                    updateStatus(ScrobbleStatus.SCROBBLED)
                    scrobbleQueue.pop(entry.id)
                    delay(100.milliseconds)
                } catch (e: SerializationException) {
                    logger.error(LogTag.SCROBBLER, "Dropping malformed server scrobble entry", e)
                    scrobbleQueue.pop(entry.id)
                } catch (e: Exception) {
                    logger.warning(LogTag.SCROBBLER, "Failed to submit listen to server, keeping queued", e)
                    updateStatus(ScrobbleStatus.FAILED)
                    break
                }
            }
        } finally {
            isProcessing = false
        }
    }

    companion object {
        const val MIN_SCROBBLE_MS = 3_000L
        private val HEARTBEAT_INTERVAL = 10.seconds
        private val COALESCE_WINDOW = 150.milliseconds
    }
}
