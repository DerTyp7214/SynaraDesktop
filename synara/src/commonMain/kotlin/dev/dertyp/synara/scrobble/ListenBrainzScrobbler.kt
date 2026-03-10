package dev.dertyp.synara.scrobble

import com.russhwolf.settings.Settings
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.nullIfEmpty
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

class ListenBrainzScrobbler(
    private val settings: Settings,
    private val musicBrainzService: MusicBrainzService,
    private val scrobbleQueue: ScrobbleQueue,
    globalJson: Json
) : BaseScrobbler() {
    override val name: String = "ListenBrainz"
    override val icon: String = ""
    override val tintIcon: Boolean = false

    private val json = Json(globalJson) {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val baseUrl = "https://api.listenbrainz.org/1"
    private val target = "listenbrainz"
    private val queueUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var isProcessing = false
    private val globalState: GlobalStateModel by inject()

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
            globalState.user.collectLatest {
                queueUpdateFlow.emit(Unit)
            }
        }
    }

    private suspend fun processQueue() {
        if (isProcessing) return
        isProcessing = true
        try {
            while (!scrobbleQueue.isEmpty(target)) {
                val entry = scrobbleQueue.peek(target) ?: break
                val success = submitListen(
                    song = entry.song,
                    listenType = ListenType.SINGLE,
                    listenedAt = entry.timestamp,
                    fromQueue = true
                )
                if (success) {
                    scrobbleQueue.pop(entry.id)
                    delay(100)
                } else {
                    break
                }
            }
        } finally {
            isProcessing = false
        }
    }

    override suspend fun newSong(song: UserSong?) {
        if (song != null) submitListen(song, ListenType.PLAYING_NOW)
    }

    override suspend fun triggered(song: UserSong) {
        submitListen(song, ListenType.SINGLE, currentTimeMillis() / 1000)
    }

    private suspend fun submitListen(
        song: UserSong,
        listenType: ListenType,
        listenedAt: Long? = null,
        fromQueue: Boolean = false
    ): Boolean {
        if (!settings.get(SettingKey.IsListenBrainzEnabled, false)) return false
        val token = settings.getStringOrNull(SettingKey.ListenBrainzToken.name) ?: return false

        if (!fromQueue && listenType == ListenType.SINGLE && !scrobbleQueue.isEmpty(target)) {
            scrobbleQueue.push(song, listenedAt ?: (currentTimeMillis() / 1000), target)
            queueUpdateFlow.emit(Unit)
            return true
        }

        val recording = musicBrainzService.searchMb(song)

        delay(50)

        val release = recording?.releases?.find {
            it.title == song.album?.name?.cleanTitle()
        } ?: recording?.releases?.firstOrNull()

        val listen = ListenPayload(
            listenType = listenType,
            payload = listOf(
                ListenData(
                    listenedAt = listenedAt,
                    trackMetadata = TrackMetadata(
                        artistName = recording?.artistCredit?.joinToString("") {
                            (it.name ?: it.artist?.name) + (it.joinphrase ?: "")
                        } ?: song.artists.joinToString(" & ") { it.name },
                        trackName = recording?.title ?: song.title.cleanTitle(),
                        releaseName = release?.title ?: song.album?.name?.cleanTitle() ?: song.title.cleanTitle(),
                        additionalInfo = AdditionalInfo(
                            mediaPlayer = "Synara",
                            submissionClient = "Synara Desktop",
                            submissionClientVersion = BuildConfig.VERSION,
                            durationMs = recording?.length ?: song.duration,
                            recordingId = recording?.id,
                            artistIds = recording?.artistCredit?.mapNotNull { it.artist?.id }?.nullIfEmpty(),
                            releaseId = release?.id
                        )
                    )
                )
            )
        )

        val success = try {
            val response = httpClient.post("$baseUrl/submit-listens") {
                header("Authorization", "Token $token")
                contentType(ContentType.Application.Json)
                setBody(listen)
            }
            val isSuccess = response.status.value in 200..299
            if (isSuccess) {
                logger.info(LogTag.LISTENBRAINZ, "Successfully submitted listen to ListenBrainz: ${song.title}")
            } else {
                logger.warning(LogTag.LISTENBRAINZ, "Failed to submit listen to ListenBrainz: ${response.status.value}", response.bodyAsText())
            }
            isSuccess
        } catch (e: Exception) {
            logger.error(LogTag.LISTENBRAINZ, "Error submitting to ListenBrainz", e)
            false
        }

        if (!success && !fromQueue && listenType == ListenType.SINGLE) {
            scrobbleQueue.push(song, listenedAt ?: (currentTimeMillis() / 1000), target)
            queueUpdateFlow.emit(Unit)
            return true // We "handled" it by queuing
        }

        return success
    }

    @Serializable
    enum class ListenType {
        @SerialName("single") SINGLE,
        @SerialName("playing_now") PLAYING_NOW
    }

    @Serializable
    data class ListenPayload(
        @SerialName("listen_type") val listenType: ListenType,
        val payload: List<ListenData>
    )

    @Serializable
    data class ListenData(
        @SerialName("listened_at") val listenedAt: Long? = null,
        @SerialName("track_metadata") val trackMetadata: TrackMetadata
    )

    @Serializable
    data class TrackMetadata(
        @SerialName("artist_name") val artistName: String,
        @SerialName("track_name") val trackName: String,
        @SerialName("release_name") val releaseName: String? = null,
        @SerialName("additional_info") val additionalInfo: AdditionalInfo
    )

    @Serializable
    data class AdditionalInfo(
        @SerialName("media_player") val mediaPlayer: String,
        @SerialName("submission_client") val submissionClient: String,
        @SerialName("submission_client_version") val submissionClientVersion: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("recording_mbid") val recordingId: String? = null,
        @SerialName("artist_mbids") val artistIds: List<String>? = null,
        @SerialName("release_mbid") val releaseId: String? = null
    )
}
