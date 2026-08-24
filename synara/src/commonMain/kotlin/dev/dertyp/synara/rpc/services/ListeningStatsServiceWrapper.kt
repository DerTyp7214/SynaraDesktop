package dev.dertyp.synara.rpc.services

import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.StatsRange
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.synara.rpc.RpcServiceManager

class ListeningStatsServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IListeningStatsService {
    override suspend fun getStats(range: StatsRange, timezone: String, topLimit: Int): ListeningStats {
        return manager.getService<IListeningStatsService>().getStats(range, timezone, topLimit)
    }

    override suspend fun linkUnmatchedTrack(request: LinkUnmatchedTrackRequest): LinkUnmatchedTrackResult {
        return manager.getService<IListeningStatsService>().linkUnmatchedTrack(request)
    }
}
