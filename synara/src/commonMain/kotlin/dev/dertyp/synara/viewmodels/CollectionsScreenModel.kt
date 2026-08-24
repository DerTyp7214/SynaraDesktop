package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.MediaCollection
import dev.dertyp.services.ICollectionService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CollectionsScreenModel(
    private val collectionService: ICollectionService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<CollectionsScreenModel.CollectionsState>(CollectionsState()) {

    data class CollectionsState(
        val collections: List<MediaCollection> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()
                val collections = collectionService.allCollections()
                mutableState.update { it.copy(collections = collections, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun createCollection(name: String, description: String?) {
        screenModelScope.launch(dispatchers.io) {
            try {
                collectionService.createCollection(
                    InsertableCollection(name = name, description = description?.takeIf { it.isNotBlank() })
                )
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            refresh()
        }
    }

    fun deleteCollection(id: PlatformUUID) {
        screenModelScope.launch(dispatchers.io) {
            try {
                collectionService.delete(id)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            refresh()
        }
    }
}
