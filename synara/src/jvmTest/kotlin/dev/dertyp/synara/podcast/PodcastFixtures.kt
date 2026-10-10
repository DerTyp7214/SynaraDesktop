package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.randomPlatformUUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

fun testEpisode(
    index: Int,
    id: PlatformUUID = randomPlatformUUID(),
    showId: PlatformUUID = randomPlatformUUID(),
    durationMs: Long? = 3_600_000L,
    progress: PodcastEpisodeProgress? = null
): PodcastEpisode = PodcastEpisode(
    id = id,
    showId = showId,
    showTitle = "Show",
    guid = "guid-$index",
    title = "Episode $index",
    publishedAt = index * 1_000L,
    durationMs = durationMs,
    progress = progress,
    createdAt = 0L,
    updatedAt = 0L
)

fun testProgress(
    episode: PodcastEpisode,
    positionMs: Long,
    updatedAt: Long,
    completed: Boolean = false,
    durationMs: Long? = null,
    deviceId: String? = null,
    lastPlayedAt: Long = updatedAt
): PodcastEpisodeProgress = PodcastEpisodeProgress(
    episodeId = episode.id,
    showId = episode.showId,
    positionMs = positionMs,
    durationMs = durationMs,
    completed = completed,
    lastPlayedAt = lastPlayedAt,
    updatedAt = updatedAt,
    deviceId = deviceId
)

class InMemoryProgressStore : PodcastProgressStore {
    private val _latest = MutableStateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>(emptyMap())
    override val latest: StateFlow<Map<PlatformUUID, PodcastEpisodeProgress>> = _latest.asStateFlow()

    private val _updates = MutableSharedFlow<PodcastEpisodeProgress>(extraBufferCapacity = 64)
    override val updates: SharedFlow<PodcastEpisodeProgress> = _updates.asSharedFlow()

    val recorded = mutableListOf<PodcastEpisodeProgress>()

    override fun record(progress: PodcastEpisodeProgress) {
        recorded += progress
        val existing = _latest.value[progress.episodeId]
        if (existing != null && existing.updatedAt >= progress.updatedAt) return
        _latest.value += (progress.episodeId to progress)
        _updates.tryEmit(progress)
    }
}
