package dev.dertyp.synara.scrobble

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.services.IScrobbleService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.server_scrobbler
import kotlin.time.Duration.Companion.milliseconds

class ServerScrobbler(
    private val scrobbleService: IScrobbleService,
    private val scrobbleQueue: ScrobbleQueue,
    private val rpcServiceManager: RpcServiceManager,
    private val playerModel: PlayerModel,
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
                .collectLatest { queueUpdateFlow.emit(Unit) }
        }
        this += scope.launch {
            playerModel.isPlaying
                .drop(1)
                .filter { it }
                .collectLatest {
                    val song = currentSong ?: return@collectLatest
                    if (!isEnabled) return@collectLatest
                    try {
                        scrobbleService.nowPlaying(song.id)
                    } catch (e: Exception) {
                        logger.warning(LogTag.SCROBBLER, "Failed to re-report now playing to server", e)
                    }
                }
        }
    }

    override suspend fun newSong(song: UserSong?) {
        currentSong = song
        if (!isEnabled) return
        try {
            if (song != null) scrobbleService.nowPlaying(song.id)
            else scrobbleService.clearNowPlaying()
        } catch (e: Exception) {
            logger.warning(LogTag.SCROBBLER, "Failed to report now playing to server", e)
        }
    }

    override suspend fun triggered(song: UserSong) {
        if (!isEnabled) return
        updateStatus(ScrobbleStatus.SCROBBLED)
    }

    override suspend fun listenEnded(song: UserSong, msPlayed: Long) {
        if (!isEnabled) return
        val listenedAt = currentTimeMillis() - msPlayed
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
                } catch (e: kotlinx.serialization.SerializationException) {
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
}
