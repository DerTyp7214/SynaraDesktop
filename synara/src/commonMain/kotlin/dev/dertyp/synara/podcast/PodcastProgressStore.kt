package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PodcastProgressStore {
    val latest: StateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>
    val updates: SharedFlow<PodcastEpisodeProgress>

    fun record(progress: PodcastEpisodeProgress)
}

fun PodcastEpisode.withProgress(progress: PodcastEpisodeProgress): PodcastEpisode {
    if (progress.episodeId != id) return this
    val current = this.progress
    if (current != null && current.updatedAt >= progress.updatedAt) return this
    return copy(progress = progress)
}

fun PodcastEpisode.withProgress(map: Map<PlatformUUID, PodcastEpisodeProgress>): PodcastEpisode {
    val entry = map[id] ?: return this
    return withProgress(entry)
}

fun List<PodcastEpisode>.withProgress(map: Map<PlatformUUID, PodcastEpisodeProgress>): List<PodcastEpisode> {
    if (map.isEmpty()) return this
    return map { it.withProgress(map) }
}
