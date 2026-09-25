package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.services.IPodcastService

sealed class PodcastContinuation {
    abstract val episode: PodcastEpisode

    data class Continue(override val episode: PodcastEpisode) : PodcastContinuation()
    data class Next(override val episode: PodcastEpisode) : PodcastContinuation()
    data class Start(override val episode: PodcastEpisode) : PodcastContinuation()
}

const val PODCAST_CONTINUATION_NEWER = 50
private const val CONTINUATION_SCAN_PAGE_SIZE = 50

suspend fun IPodcastService.resolveContinuation(showId: PlatformUUID): PodcastContinuation? {
    val lastPlayed = getLastPlayed(includeCompleted = true)?.takeIf { it.showId == showId }
        ?: getEpisodes(showId, 0, CONTINUATION_SCAN_PAGE_SIZE, newestFirst = true).data
            .filter { it.progress != null && (it.isPlayed || it.positionMs > 0) }
            .maxByOrNull { it.progress?.lastPlayedAt ?: 0L }

    if (lastPlayed != null) {
        if (!lastPlayed.isPlayed) return PodcastContinuation.Continue(lastPlayed)
        val next = getEpisodeWindow(lastPlayed.id, older = 0, newer = 1)
            .dropWhile { it.id != lastPlayed.id }
            .drop(1)
            .firstOrNull()
        if (next != null) {
            return if (next.isInProgress) PodcastContinuation.Continue(next) else PodcastContinuation.Next(next)
        }
        return null
    }

    return getEpisodes(showId, 0, 1, newestFirst = false).data.firstOrNull()?.let { PodcastContinuation.Start(it) }
}

suspend fun IPodcastService.continuationQueue(target: PodcastEpisode): List<PodcastEpisode> {
    val window = getEpisodeWindow(target.id, older = 0, newer = PODCAST_CONTINUATION_NEWER)
        .dropWhile { it.id != target.id }
    return window.ifEmpty { listOf(target) }
}
