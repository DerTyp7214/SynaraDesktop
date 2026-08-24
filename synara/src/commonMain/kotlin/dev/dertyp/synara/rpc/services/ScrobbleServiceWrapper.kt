package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RecentListens
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.services.IScrobbleService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ScrobbleServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IScrobbleService {
    override suspend fun nowPlaying(songId: PlatformUUID) {
        manager.getService<IScrobbleService>().nowPlaying(songId)
    }

    override suspend fun clearNowPlaying() {
        manager.getService<IScrobbleService>().clearNowPlaying()
    }

    override suspend fun listened(request: ScrobbleRequest) {
        manager.getService<IScrobbleService>().listened(request)
    }

    override fun recentListensFlow(limit: Int): Flow<RecentListens> {
        return manager.getService<IScrobbleService>().recentListensFlow(limit)
    }
}
