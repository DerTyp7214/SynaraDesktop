package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.CollectionSearchResults
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.MediaCollection
import dev.dertyp.services.ICollectionService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class CollectionServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ICollectionService {
    override suspend fun byId(id: PlatformUUID): MediaCollection? {
        return manager.getService<ICollectionService>().byId(id)
    }

    override suspend fun allCollections(): List<MediaCollection> {
        return manager.getService<ICollectionService>().allCollections()
    }

    override suspend fun createCollection(collection: InsertableCollection): PlatformUUID {
        return manager.getService<ICollectionService>().createCollection(collection)
    }

    override suspend fun updateCollection(id: PlatformUUID, collection: InsertableCollection): Boolean {
        return manager.getService<ICollectionService>().updateCollection(id, collection)
    }

    override suspend fun addItem(id: PlatformUUID, itemType: CollectionItemType, itemId: PlatformUUID): Boolean {
        return manager.getService<ICollectionService>().addItem(id, itemType, itemId)
    }

    override suspend fun removeItem(id: PlatformUUID, itemType: CollectionItemType, itemId: PlatformUUID): Boolean {
        return manager.getService<ICollectionService>().removeItem(id, itemType, itemId)
    }

    override suspend fun setCollectionImage(id: PlatformUUID, imageId: PlatformUUID?): Boolean {
        return manager.getService<ICollectionService>().setCollectionImage(id, imageId)
    }

    override suspend fun delete(id: PlatformUUID): Boolean {
        return manager.getService<ICollectionService>().delete(id)
    }

    override suspend fun rankedSearch(
        collectionId: PlatformUUID,
        query: String,
        explicit: Boolean,
        page: Int,
        pageSize: Int
    ): CollectionSearchResults {
        return manager.getService<ICollectionService>().rankedSearch(collectionId, query, explicit, page, pageSize)
    }

    override fun songIds(collectionId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ICollectionService>().songIds(collectionId)
    }

    override fun albumIds(collectionId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ICollectionService>().albumIds(collectionId)
    }

    override fun artistIds(collectionId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ICollectionService>().artistIds(collectionId)
    }

    override fun playlistIds(collectionId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ICollectionService>().playlistIds(collectionId)
    }
}
