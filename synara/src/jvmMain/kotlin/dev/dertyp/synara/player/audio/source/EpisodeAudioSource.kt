package dev.dertyp.synara.player.audio.source

import dev.dertyp.data.PodcastEpisode
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.FlowInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream

class EpisodeAudioSource(
    val episode: PodcastEpisode,
    private val podcastService: IPodcastService
) : AudioSource {
    @Volatile
    private var knownSize: Long? = null

    override val cacheKey: String = "episode:${episode.id}:${if (episode.imported) "stored" else "relay"}"
    override val durationMsHint: Long? get() = episode.durationMs?.takeIf { it > 0 }
    override val bitRateHint: Long? get() = null
    override val formatHint: String? get() = episode.format ?: episode.enclosureType

    override suspend fun size(): Long {
        knownSize?.let { return it }
        val size = try {
            podcastService.getStreamSize(episode.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0L
        }
        val resolved = if (size > 0) size else episode.fileSize ?: episode.enclosureLength ?: 0L
        knownSize = resolved
        return resolved
    }

    override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
        val flow = podcastService.streamEpisode(episode.id, offset, CHUNK_SIZE) ?: return null
        return FlowInputStream(flow, scope)
    }

    companion object {
        private const val CHUNK_SIZE = 64 * 1024
    }
}
