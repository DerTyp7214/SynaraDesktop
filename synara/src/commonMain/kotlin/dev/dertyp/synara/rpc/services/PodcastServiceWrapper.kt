package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class PodcastServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IPodcastService {
    override suspend fun subscribe(feedUrl: String): PodcastShow {
        return manager.getService<IPodcastService>().subscribe(feedUrl)
    }

    override suspend fun subscribeToShow(showId: PlatformUUID): PodcastShow {
        return manager.getService<IPodcastService>().subscribeToShow(showId)
    }

    override suspend fun unsubscribe(showId: PlatformUUID): Boolean {
        return manager.getService<IPodcastService>().unsubscribe(showId)
    }

    override suspend fun getSubscriptions(): List<PodcastShow> {
        return manager.getService<IPodcastService>().getSubscriptions()
    }

    override suspend fun browseShows(query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastShow> {
        return manager.getService<IPodcastService>().browseShows(query, page, pageSize)
    }

    override suspend fun searchIndex(query: String, limit: Int, indexes: List<String>): List<PodcastIndexResult> {
        return manager.getService<IPodcastService>().searchIndex(query, limit, indexes)
    }

    override suspend fun getIndexes(): List<PodcastIndexInfo> {
        return manager.getService<IPodcastService>().getIndexes()
    }

    override suspend fun getShow(showId: PlatformUUID): PodcastShow? {
        return manager.getService<IPodcastService>().getShow(showId)
    }

    override suspend fun getEpisodes(showId: PlatformUUID, page: Int, pageSize: Int, newestFirst: Boolean): PaginatedResponse<PodcastEpisode> {
        return manager.getService<IPodcastService>().getEpisodes(showId, page, pageSize, newestFirst)
    }

    override suspend fun getEpisode(episodeId: PlatformUUID): PodcastEpisode? {
        return manager.getService<IPodcastService>().getEpisode(episodeId)
    }

    override suspend fun getEpisodesByIds(episodeIds: List<PlatformUUID>): List<PodcastEpisode> {
        return manager.getService<IPodcastService>().getEpisodesByIds(episodeIds)
    }

    override suspend fun getEpisodeWindow(episodeId: PlatformUUID, older: Int, newer: Int): List<PodcastEpisode> {
        return manager.getService<IPodcastService>().getEpisodeWindow(episodeId, older, newer)
    }

    override suspend fun searchEpisodes(query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        return manager.getService<IPodcastService>().searchEpisodes(query, page, pageSize)
    }

    override suspend fun getLatestEpisodes(page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        return manager.getService<IPodcastService>().getLatestEpisodes(page, pageSize)
    }

    override suspend fun getInProgress(page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        return manager.getService<IPodcastService>().getInProgress(page, pageSize)
    }

    override suspend fun getLastPlayed(includeCompleted: Boolean): PodcastEpisode? {
        return manager.getService<IPodcastService>().getLastPlayed(includeCompleted)
    }

    override suspend fun reportPlayback(report: EpisodePlaybackReport): PodcastEpisodeProgress {
        return manager.getService<IPodcastService>().reportPlayback(report)
    }

    override suspend fun setPlayed(episodeId: PlatformUUID, played: Boolean): PodcastEpisodeProgress {
        return manager.getService<IPodcastService>().setPlayed(episodeId, played)
    }

    override fun observeProgress(): Flow<PodcastEpisodeProgress> {
        return manager.getService<IPodcastService>().observeProgress()
    }

    override suspend fun refreshShow(showId: PlatformUUID): PodcastShow {
        return manager.getService<IPodcastService>().refreshShow(showId)
    }

    override suspend fun updateShowSettings(showId: PlatformUUID, settings: PodcastShowSettings): PodcastShow {
        return manager.getService<IPodcastService>().updateShowSettings(showId, settings)
    }

    override suspend fun importEpisode(episodeId: PlatformUUID): PodcastEpisode {
        return manager.getService<IPodcastService>().importEpisode(episodeId)
    }

    override suspend fun removeImport(episodeId: PlatformUUID): PodcastEpisode {
        return manager.getService<IPodcastService>().removeImport(episodeId)
    }

    override suspend fun deleteShow(showId: PlatformUUID): Boolean {
        return manager.getService<IPodcastService>().deleteShow(showId)
    }

    override suspend fun scanLocal(): PodcastScanResult {
        return manager.getService<IPodcastService>().scanLocal()
    }

    override fun streamEpisode(episodeId: PlatformUUID, offset: Long, chunkSize: Int): Flow<ByteArray>? {
        return manager.getService<IPodcastService>().streamEpisode(episodeId, offset, chunkSize)
    }

    override suspend fun getStreamSize(episodeId: PlatformUUID): Long {
        return manager.getService<IPodcastService>().getStreamSize(episodeId)
    }

    override suspend fun getTranscripts(episodeId: PlatformUUID): List<PodcastTranscript> {
        return manager.getService<IPodcastService>().getTranscripts(episodeId)
    }

    override suspend fun getTranscript(transcriptId: PlatformUUID): PodcastTranscriptContent? {
        return manager.getService<IPodcastService>().getTranscript(transcriptId)
    }
}
