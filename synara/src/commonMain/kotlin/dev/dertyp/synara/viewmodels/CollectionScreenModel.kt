package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.CollectionSearchResults
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.MediaCollection
import dev.dertyp.services.ICollectionService
import dev.dertyp.services.IImageService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class CollectionScreenModel(
    private val collectionId: PlatformUUID,
    private val collectionService: ICollectionService,
    private val imageService: IImageService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<CollectionScreenModel.CollectionState>(CollectionState()), Refreshable {

    private val refresher = RefreshCoalescer(screenModelScope, dispatchers.io) { fetchCollection() }
    override val isRefreshing = refresher.isRefreshing

    data class CollectionState(
        val collection: MediaCollection? = null,
        val results: CollectionSearchResults? = null,
        val query: String = "",
        val isLoading: Boolean = false,
        val isDeleted: Boolean = false,
        val error: String? = null
    )

    init {
        refresh()
    }

    override fun refresh() {
        refresher.refresh()
    }

    private suspend fun fetchCollection() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        try {
            rpcServiceManager.awaitAuthentication()
            val collection = collectionService.byId(collectionId)
            mutableState.update { it.copy(collection = collection) }
            loadContent(state.value.query)
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
        }
    }

    private var searchJob: Job? = null

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = screenModelScope.launch(dispatchers.io) {
            delay(300.milliseconds)
            loadContent(query)
        }
    }

    private suspend fun loadContent(query: String) {
        mutableState.update { it.copy(isLoading = true) }
        try {
            val results = collectionService.rankedSearch(collectionId, query, explicit = true)
            mutableState.update { it.copy(results = results, isLoading = false) }
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun removeItem(type: CollectionItemType, itemId: PlatformUUID) {
        screenModelScope.launch(dispatchers.io) {
            try {
                collectionService.removeItem(collectionId, type, itemId)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            fetchCollection()
        }
    }

    fun updateCollection(name: String, description: String?) {
        val current = state.value.collection ?: return
        screenModelScope.launch(dispatchers.io) {
            try {
                collectionService.updateCollection(
                    collectionId,
                    InsertableCollection(
                        name = name,
                        description = description?.takeIf { it.isNotBlank() },
                        imageId = current.imageId
                    )
                )
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            fetchCollection()
        }
    }

    suspend fun setCover(bytes: ByteArray) {
        try {
            val imageId = imageService.createImage(bytes)
            collectionService.setCollectionImage(collectionId, imageId)
            fetchCollection()
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message) }
        }
    }

    fun delete() {
        screenModelScope.launch(dispatchers.io) {
            try {
                collectionService.delete(collectionId)
                mutableState.update { it.copy(isDeleted = true) }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
        }
    }
}
