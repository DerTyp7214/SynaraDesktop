package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkedListenGroup
import dev.dertyp.data.LinkedListenGroupKey
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.UserSong
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.synara.rpc.RpcServiceManager

class ListeningStatsServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IListeningStatsService {
    override suspend fun getStats(
        range: StatsRange,
        timezone: String,
        topLimit: Int,
        topOrder: TopOrder,
    ): ListeningStats {
        return manager.getService<IListeningStatsService>().getStats(range, timezone, topLimit, topOrder)
    }

    override suspend fun getTopSongs(
        range: StatsRange,
        timezone: String,
        limit: Int,
        order: TopOrder,
    ): List<UserSong> {
        return manager.getService<IListeningStatsService>().getTopSongs(range, timezone, limit, order)
    }

    override suspend fun linkUnmatchedTrack(request: LinkUnmatchedTrackRequest): LinkUnmatchedTrackResult {
        return manager.getService<IListeningStatsService>().linkUnmatchedTrack(request)
    }

    override suspend fun getLinkedListenGroups(songId: PlatformUUID): List<LinkedListenGroup> {
        return manager.getService<IListeningStatsService>().getLinkedListenGroups(songId)
    }

    override suspend fun relinkSong(
        fromSongId: PlatformUUID,
        toSongId: PlatformUUID,
        groups: List<LinkedListenGroupKey>?,
    ): LinkUnmatchedTrackResult {
        return manager.getService<IListeningStatsService>().relinkSong(fromSongId, toSongId, groups)
    }
}
