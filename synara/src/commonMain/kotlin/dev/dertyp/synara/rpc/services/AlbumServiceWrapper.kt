package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.services.IAlbumService
import dev.dertyp.synara.rpc.RpcServiceManager

class AlbumServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IAlbumService {
    override suspend fun byId(id: PlatformUUID): Album? {
        return manager.getService<IAlbumService>().byId(id)
    }

    override suspend fun byIds(ids: List<PlatformUUID>): List<Album> {
        return manager.getService<IAlbumService>().byIds(ids)
    }

    override suspend fun versions(id: PlatformUUID): List<Album> {
        return manager.getService<IAlbumService>().versions(id)
    }

    override suspend fun byName(page: Int, pageSize: Int, name: String): PaginatedResponse<Album> {
        return manager.getService<IAlbumService>().byName(page, pageSize, name)
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Album> {
        return manager.getService<IAlbumService>().rankedSearch(page, pageSize, query)
    }

    override suspend fun allAlbums(page: Int, pageSize: Int): PaginatedResponse<Album> {
        return manager.getService<IAlbumService>().allAlbums(page, pageSize)
    }

    override suspend fun deleteAlbums(ids: List<PlatformUUID>): Boolean {
        return manager.getService<IAlbumService>().deleteAlbums(ids)
    }

    override suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: PlatformUUID,
        singles: Boolean
    ): PaginatedResponse<Album> {
        return manager.getService<IAlbumService>().byArtist(page, pageSize, artistId, singles)
    }
}
