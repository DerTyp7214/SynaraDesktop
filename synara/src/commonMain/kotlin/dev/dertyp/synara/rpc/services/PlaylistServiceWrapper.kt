package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.PlaylistEntry
import dev.dertyp.services.IPlaylistService
import dev.dertyp.synara.rpc.RpcServiceManager

class PlaylistServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IPlaylistService {
    override suspend fun byId(id: PlatformUUID): Playlist? {
        return manager.getService<IPlaylistService>().byId(id)
    }

    override suspend fun byIds(ids: List<PlatformUUID>): List<Playlist> {
        return manager.getService<IPlaylistService>().byIds(ids)
    }

    override suspend fun byIdFull(id: PlatformUUID): Pair<String, List<PlaylistEntry>>? {
        return manager.getService<IPlaylistService>().byIdFull(id)
    }

    override suspend fun byName(name: String): Playlist? {
        return manager.getService<IPlaylistService>().byName(name)
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Playlist> {
        return manager.getService<IPlaylistService>().rankedSearch(page, pageSize, query)
    }

    override suspend fun allPlaylists(page: Int, pageSize: Int): PaginatedResponse<Playlist> {
        return manager.getService<IPlaylistService>().allPlaylists(page, pageSize)
    }

    override suspend fun delete(id: PlatformUUID): Boolean {
        return manager.getService<IPlaylistService>().delete(id)
    }
}
