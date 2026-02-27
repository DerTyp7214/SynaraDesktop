package dev.dertyp.synara.rpc.services

import dev.dertyp.data.ServerStats
import dev.dertyp.services.IServerStatsService
import dev.dertyp.synara.rpc.RpcServiceManager

class ServerStatsServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IServerStatsService {
    override suspend fun getStats(): ServerStats {
        return manager.getServerStatsService().getStats()
    }

    override suspend fun health(): Boolean {
        return manager.getServerStatsService().health()
    }
}
