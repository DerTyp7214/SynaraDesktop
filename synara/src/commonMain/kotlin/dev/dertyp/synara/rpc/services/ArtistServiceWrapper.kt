package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.SplitArtist
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.rpc.RpcServiceManager

class ArtistServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IArtistService {
    override suspend fun byId(id: PlatformUUID): Artist? {
        return manager.getService<IArtistService>().byId(id)
    }

    override suspend fun byIds(ids: List<PlatformUUID>): List<Artist> {
        return manager.getService<IArtistService>().byIds(ids)
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().rankedSearch(page, pageSize, query)
    }

    override suspend fun byGroup(page: Int, pageSize: Int, groupId: PlatformUUID): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().byGroup(page, pageSize, groupId)
    }

    override suspend fun mergeArtists(mergeArtists: MergeArtists): Artist? {
        return manager.getService<IArtistService>().mergeArtists(mergeArtists)
    }

    override suspend fun splitArtist(splitArtist: SplitArtist): List<Artist> {
        return manager.getService<IArtistService>().splitArtist(splitArtist)
    }

    override suspend fun allArtists(page: Int, pageSize: Int): PaginatedResponse<Artist> {
        return manager.getService<IArtistService>().allArtists(page, pageSize)
    }
}
