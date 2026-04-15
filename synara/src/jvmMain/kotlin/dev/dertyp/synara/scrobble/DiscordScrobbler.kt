package dev.dertyp.synara.scrobble

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.joinArtists
import dev.dertyp.data.UserSong
import dev.dertyp.logging.LogTag
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.discord_rpc
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

actual class DiscordScrobbler actual constructor(
    private val settings: Settings,
    private val playerModel: PlayerModel,
    private val metadataService: IMetadataService
) : BaseScrobbler() {
    override val name = Res.string.discord_rpc
    override val icon: SynaraIcons = SynaraIcons.Discord
    override val sortOrder: Int = 3
    override val showInDialog: Boolean = false

    private var client: DiscordIpcClient? = null
    private var isClientRunning = false
    private val applicationId = if (BuildConfig.IS_DEBUG) "1435589125301600356"
    else "1451162371497263104"

    private var updateJob: Job? = null

    private val imageCache =
        Collections.synchronizedMap(object : LinkedHashMap<PlatformUUID, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlatformUUID, String>?): Boolean {
                return size > 1000
            }
        })

    private val imageJobs = mutableMapOf<PlatformUUID, Deferred<String?>>()

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
                updateStatus(ScrobbleStatus.IDLE)
            }
            if (!settings.get(SettingKey.IsDiscordRpcEnabled, false)) {
                stopClient()
            }
            return
        }

        if (updateJob == null || !updateJob!!.isActive) {
            updateStatus(ScrobbleStatus.SCROBBLED)
            updateJob = scope.launch {
                while (isActive && isRunning) {
                    updatePresence(playerModel.currentSong.value, playerModel.isPlaying.value)
                    delay(1.seconds)
                }
            }
        }
    }

    private fun pad(input: String?): String {
        val s = input ?: ""
        return if (s.length < 2) s.padEnd(2, ' ') else s
    }

    private suspend fun getImageUrl(imageId: PlatformUUID): String? {
        imageCache[imageId]?.let { return it }

        val deferred = synchronized(imageJobs) {
            imageJobs.getOrPut(imageId) {
                scope.async {
                    try {
                        val url = metadataService.getImageUrlByImageId(IMetadataService.MetadataType.imageCache, imageId)
                        imageCache[imageId] = url
                        url
                    } catch (e: Exception) {
                        logger.error(
                            LogTag.SCROBBLER,
                            "Failed to fetch image URL from cache: ${e.message}"
                        )
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

        val imageUrl = song.coverId?.let { getImageUrl(it) }

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
