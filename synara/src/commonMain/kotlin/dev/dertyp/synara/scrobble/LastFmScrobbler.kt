package dev.dertyp.synara.scrobble

import com.russhwolf.settings.Settings
import dev.dertyp.core.md5
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.inject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.lastfm
import kotlin.time.Duration.Companion.milliseconds

class LastFmScrobbler(
    private val settings: Settings,
    private val scrobbleQueue: ScrobbleQueue,
    globalJson: Json
) : BaseScrobbler() {
    override val name = Res.string.lastfm
    override val icon: SynaraIcons = SynaraIcons.LastFm
    override val sortOrder: Int = 2

    private val json = Json(globalJson) { encodeDefaults = false }
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val baseUrl = "https://ws.audioscrobbler.com/2.0/"
    private val target = "lastfm"
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
                    isNowPlaying = false,
                    timestamp = entry.timestamp,
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
        if (song != null) submitListen(song, true)
    }

    override suspend fun triggered(song: UserSong) {
        updateStatus(ScrobbleStatus.QUEUED)
        submitListen(song, false, currentTimeMillis() / 1000)
    }

    suspend fun getMobileSession(username: String, password: String): LastFmSession? {
        val apiKey = settings.getStringOrNull(SettingKey.LastFmApiKey.name) ?: return null
        val sharedSecret = settings.getStringOrNull(SettingKey.LastFmSharedSecret.name) ?: return null

        val params = mapOf(
            "method" to "auth.getMobileSession",
            "username" to username,
            "password" to password,
            "api_key" to apiKey
        )

        val apiSig = generateSignature(params, sharedSecret)

        return try {
            val response: LastFmSessionResponse = httpClient.submitForm(
                url = "$baseUrl?format=json",
                formParameters = parameters {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                }
            ).body()

            response.session
        } catch (e: Exception) {
            logger.error(LogTag.LASTFM, "Error getting mobile session", e)
            null
        }
    }

    private suspend fun submitListen(
        song: UserSong,
        isNowPlaying: Boolean,
        timestamp: Long? = null,
        fromQueue: Boolean = false
    ): Boolean {
        if (!settings.get(SettingKey.IsLastFmEnabled, false)) return false
        val apiKey = settings.getStringOrNull(SettingKey.LastFmApiKey.name) ?: return false
        val sharedSecret = settings.getStringOrNull(SettingKey.LastFmSharedSecret.name) ?: return false
        val sessionKey = settings.getStringOrNull(SettingKey.LastFmSessionKey.name) ?: return false

        if (!fromQueue && !isNowPlaying && !scrobbleQueue.isEmpty(target)) {
            scrobbleQueue.push(song, timestamp ?: (currentTimeMillis() / 1000), target)
            queueUpdateFlow.emit(Unit)
            return true
        }

        val params = mutableMapOf(
            "method" to if (isNowPlaying) "track.updateNowPlaying" else "track.scrobble",
            "api_key" to apiKey,
            "sk" to sessionKey,
            "artist" to song.artists.joinToString(", ") { it.name },
            "track" to song.title
        )
        song.album?.name?.let { params["album"] = it }
        if (!isNowPlaying) {
            params["timestamp"] = (timestamp ?: (currentTimeMillis() / 1000)).toString()
        }

        val apiSig = generateSignature(params.filter { it.key != "format" }, sharedSecret)
        params["api_sig"] = apiSig

        val success = try {
            val response = httpClient.submitForm(
                url = "$baseUrl?format=json",
                formParameters = parameters {
                    params.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                }
            )
            val isSuccess = response.status.value in 200..299
            if (isSuccess) {
                logger.info(LogTag.LASTFM, "Successfully submitted to Last.fm: ${song.title}")
                if (!isNowPlaying) updateStatus(ScrobbleStatus.SCROBBLED)
            } else {
                logger.warning(LogTag.LASTFM, "Failed to submit to Last.fm: ${response.status.value}")
                if (!isNowPlaying) updateStatus(ScrobbleStatus.FAILED)
            }
            isSuccess
        } catch (e: Exception) {
            logger.error(LogTag.LASTFM, "Error submitting to Last.fm", e)
            if (!isNowPlaying) updateStatus(ScrobbleStatus.FAILED)
            false
        }

        if (!success && !fromQueue && !isNowPlaying) {
            scrobbleQueue.push(song, timestamp ?: (currentTimeMillis() / 1000), target)
            queueUpdateFlow.emit(Unit)
            return true
        }

        return success
    }

    private fun generateSignature(params: Map<String, String>, secret: String): String {
        val signature = params.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key$value"
        } + secret
        return signature.md5()
    }

    @Serializable
    data class LastFmSessionResponse(
        val session: LastFmSession? = null,
        val error: Int? = null,
        val message: String? = null
    )

    @Serializable
    data class LastFmSession(
        val name: String,
        val key: String,
        val subscriber: Int
    )
}
