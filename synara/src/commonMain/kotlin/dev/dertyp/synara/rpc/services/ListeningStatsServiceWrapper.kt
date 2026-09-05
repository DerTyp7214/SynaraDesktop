package dev.dertyp.synara.rpc.services

import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
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

    override suspend fun linkUnmatchedTrack(request: LinkUnmatchedTrackRequest): LinkUnmatchedTrackResult {
        return manager.getService<IListeningStatsService>().linkUnmatchedTrack(request)
    }
}
