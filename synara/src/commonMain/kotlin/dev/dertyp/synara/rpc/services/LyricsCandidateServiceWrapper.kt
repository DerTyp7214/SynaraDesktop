package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.models.*
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class LyricsCandidateServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ILyricsCandidateService {
    override suspend fun getLyrics(songId: PlatformUUID, candidateId: PlatformUUID?): LyricsCandidate? {
        return manager.getService<ILyricsCandidateService>().getLyrics(songId, candidateId)
    }

    override suspend fun getLyricsInfo(songIds: List<PlatformUUID>): Map<PlatformUUID, SongLyricsInfo> {
        return manager.getService<ILyricsCandidateService>().getLyricsInfo(songIds)
    }

    override suspend fun findLyrics(songId: PlatformUUID, providers: List<String>?, refresh: Boolean): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().findLyrics(songId, providers, refresh)
    }

    override suspend fun findLyricsByQuery(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        providers: List<String>?
    ): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().findLyricsByQuery(title, artist, album, durationMs, providers)
    }

    override suspend fun getLyricsCandidates(songId: PlatformUUID, includeContent: Boolean): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().getLyricsCandidates(songId, includeContent)
    }

    override suspend fun getLyricsCandidate(candidateId: PlatformUUID): LyricsCandidate? {
        return manager.getService<ILyricsCandidateService>().getLyricsCandidate(candidateId)
    }

    override suspend fun setSyncedLyrics(songId: PlatformUUID, lyrics: SyncedLyrics?): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().setSyncedLyrics(songId, lyrics)
    }

    override suspend fun importSyncedLyrics(songId: PlatformUUID, file: LyricsImport): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().importSyncedLyrics(songId, file)
    }

    override suspend fun setPreferredLyrics(songId: PlatformUUID, candidateId: PlatformUUID?): LyricsSearchResult {
        return manager.getService<ILyricsCandidateService>().setPreferredLyrics(songId, candidateId)
    }

    override suspend fun getLyricsProviders(): List<LyricsProviderInfo> {
        return manager.getService<ILyricsCandidateService>().getLyricsProviders()
    }

    override fun streamLyrics(songId: PlatformUUID, providers: List<String>?): Flow<LyricsCandidate> {
        return manager.getService<ILyricsCandidateService>().streamLyrics(songId, providers)
    }
}
