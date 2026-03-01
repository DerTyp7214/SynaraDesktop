package dev.dertyp.synara.scrobble

import com.russhwolf.settings.Settings
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.joinArtists
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.component.inject
import java.util.Collections

actual class DiscordScrobbler actual constructor(
    private val settings: Settings,
    private val playerModel: PlayerModel
) : BaseScrobbler() {
    override val name: String = "Discord RPC"
    override val icon: String = ""
    override val tintIcon: Boolean = false

    private val rpcServiceManager: RpcServiceManager by inject()
    private val httpClient: HttpClient by inject()

    private var client: DiscordIpcClient? = null
    private var isClientRunning = false
    private val applicationId = "1435589125301600356"

    private var updateJob: Job? = null

    private val imageCache = Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 1000
        }
    })

    private val imageJobs = mutableMapOf<String, Deferred<String?>>()

    override fun onStart() {
        this += scope.launch {
            playerModel.currentSong.collectLatest { song ->
                handlePlaybackChange(song, playerModel.isPlaying.value)
            }
        }

        this += scope.launch {
            playerModel.isPlaying.collectLatest { isPlaying ->
                handlePlaybackChange(playerModel.currentSong.value, isPlaying)
            }
        }
    }

    override fun onStop() {
        stopClient()
    }

    private fun handlePlaybackChange(song: UserSong?, isPlaying: Boolean) {
        if (!settings.get(SettingKey.IsDiscordRpcEnabled, false) || song == null || !isPlaying) {
            updateJob?.cancel()
            updateJob = null
            if (song == null || !isPlaying) {
                clearPresence()
            }
            if (!settings.get(SettingKey.IsDiscordRpcEnabled, false)) {
                stopClient()
            }
            return
        }

        if (updateJob == null || !updateJob!!.isActive) {
            updateJob = scope.launch {
                while (isActive && isRunning) {
                    updatePresence(playerModel.currentSong.value, playerModel.isPlaying.value)
                    delay(1000)
                }
            }
        }
    }

    private fun pad(input: String?): String {
        val s = input ?: ""
        return if (s.length < 2) s.padEnd(2, ' ') else s
    }

    private suspend fun getImageUrl(imageId: String): String? {
        imageCache[imageId]?.let { return it }

        val deferred = synchronized(imageJobs) {
            imageJobs.getOrPut(imageId) {
                scope.async {
                    try {
                        val host = rpcServiceManager.host ?: return@async null
                        val port = rpcServiceManager.port ?: return@async null
                        val token = rpcServiceManager.getAuthToken() ?: return@async null

                        val response = httpClient.get("http://$host:$port/metadata/imageCache/imageUrlById/$imageId") {
                            header("Authorization", "Bearer $token")
                        }
                        if (response.status.value in 200..299) {
                            val url = response.bodyAsText()
                            imageCache[imageId] = url
                            url
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        logger.error(LogTag.SCROBBLER, "Failed to fetch image URL from cache: ${e.message}")
                        null
                    } finally {
                        @Suppress("DeferredResultUnused")
                        synchronized(imageJobs) {
                            imageJobs.remove(imageId)
                        }
                    }
                }
            }
        }

        return deferred.await()
    }

    private suspend fun updatePresence(song: UserSong?, isPlaying: Boolean) {
        if (song == null || !isPlaying) return

        if (!isClientRunning) {
            startClient()
        }

        val imageUrl = song.coverId?.let { getImageUrl(it.toString()) }

        val activity = buildJsonObject {
            put("type", 2)
            put("details", pad(song.title.cleanTitle()))
            put("state", pad(song.artists.joinArtists()))

            put("assets", buildJsonObject {
                put("large_image", imageUrl ?: "synara-icon")
                put("large_text", pad(song.album?.name?.cleanTitle() ?: "Synara"))
            })

            val currentPos = playerModel.currentPosition.value
            val duration = playerModel.duration.value
            if (duration > 0) {
                val now = (System.currentTimeMillis() + 500) / 1000
                val start = now - (currentPos / 1000)
                val end = start + (duration / 1000)

                put("timestamps", buildJsonObject {
                    put("start", start)
                    put("end", end)
                })
            }

            put("instance", false)
        }

        client?.setActivity(activity)
    }

    private fun startClient() {
        client = DiscordIpcClient(applicationId, logger)
        if (client?.connect() == true) {
            isClientRunning = true
            logger.info(LogTag.SCROBBLER, "Discord RPC Client started")
        } else {
            client = null
            logger.error(LogTag.SCROBBLER, "Failed to start Discord RPC Client")
        }
    }

    private fun stopClient() {
        updateJob?.cancel()
        updateJob = null
        clearPresence()
        client?.close()
        client = null
        isClientRunning = false
        logger.info(LogTag.SCROBBLER, "Discord RPC Client stopped")
    }

    private fun clearPresence() {
        client?.setActivity(null)
    }
}
