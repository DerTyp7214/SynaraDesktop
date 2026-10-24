package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.EntityChange
import dev.dertyp.data.EntityChangeWindow
import dev.dertyp.services.IEntityChangeService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class EntityChangeServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IEntityChangeService {
    override suspend fun getWindow(): EntityChangeWindow {
        return manager.getService<IEntityChangeService>().getWindow()
    }

    override fun allChanges(since: Long): Flow<EntityChange> {
        return manager.getService<IEntityChangeService>().allChanges(since)
    }

    override fun byArtist(artistId: PlatformUUID, since: Long): Flow<EntityChange> {
        return manager.getService<IEntityChangeService>().byArtist(artistId, since)
    }

    override fun byAlbum(albumId: PlatformUUID, since: Long): Flow<EntityChange> {
        return manager.getService<IEntityChangeService>().byAlbum(albumId, since)
    }

    override fun byPlaylist(playlistId: PlatformUUID, since: Long): Flow<EntityChange> {
        return manager.getService<IEntityChangeService>().byPlaylist(playlistId, since)
    }

    override fun byCollection(collectionId: PlatformUUID, since: Long): Flow<EntityChange> {
        return manager.getService<IEntityChangeService>().byCollection(collectionId, since)
    }
}
