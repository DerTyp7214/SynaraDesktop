package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.models.FollowedArtist
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.synara.rpc.RpcServiceManager

class ReleaseServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IReleaseService {
    override suspend fun followArtist(musicBrainzId: String): Boolean {
        return manager.getService<IReleaseService>().followArtist(musicBrainzId)
    }

    override suspend fun unfollowArtist(artistId: PlatformUUID): Boolean {
        return manager.getService<IReleaseService>().unfollowArtist(artistId)
    }

    override suspend fun getFollowedArtists(): List<FollowedArtist> {
        return manager.getService<IReleaseService>().getFollowedArtists()
    }

    override suspend fun getRecentReleases(page: Int, pageSize: Int): PaginatedResponse<RecentRelease> {
        return manager.getService<IReleaseService>().getRecentReleases(page, pageSize)
    }
}
