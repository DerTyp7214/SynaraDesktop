package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.rpc.RpcServiceManager

class UserPlaylistServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IUserPlaylistService {
    override suspend fun byId(id: PlatformUUID): UserPlaylist? {
        return manager.getService<IUserPlaylistService>().byId(id)
    }

    override suspend fun byIds(ids: List<PlatformUUID>): List<UserPlaylist> {
        return manager.getService<IUserPlaylistService>().byIds(ids)
    }

    override suspend fun rankedSearch(
        creator: PlatformUUID?,
        page: Int,
        pageSize: Int,
        query: String
    ): PaginatedResponse<UserPlaylist> {
        return manager.getService<IUserPlaylistService>().rankedSearch(creator, page, pageSize, query)
    }

    override suspend fun allPlaylists(
        creator: PlatformUUID?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<UserPlaylist> {
        return manager.getService<IUserPlaylistService>().allPlaylists(creator, page, pageSize)
    }

    override suspend fun delete(id: PlatformUUID): Boolean {
        return manager.getService<IUserPlaylistService>().delete(id)
    }

    override suspend fun getOrAddPlaylist(
        user: User,
        customIdentifier: String?,
        playlist: InsertablePlaylist
    ): PlatformUUID {
        return manager.getService<IUserPlaylistService>().getOrAddPlaylist(user, customIdentifier, playlist)
    }

    override suspend fun addToPlaylist(
        id: PlatformUUID,
        songIds: List<Pair<Long, PlatformUUID>>
    ): List<PlatformUUID> {
        return manager.getService<IUserPlaylistService>().addToPlaylist(id, songIds)
    }

    override suspend fun removeFromPlaylist(id: PlatformUUID, songIds: List<PlatformUUID>): Int {
        return manager.getService<IUserPlaylistService>().removeFromPlaylist(id, songIds)
    }

    override suspend fun setPlaylistImage(id: PlatformUUID, imageId: PlatformUUID?): Boolean {
        return manager.getService<IUserPlaylistService>().setPlaylistImage(id, imageId)
    }
}
