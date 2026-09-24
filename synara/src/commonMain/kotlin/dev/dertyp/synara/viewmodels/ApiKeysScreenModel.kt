package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.ApiKeyInfo
import dev.dertyp.data.ApiKeyScopeInfo
import dev.dertyp.services.IApiKeyService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ApiKeysScreenModel(
    private val apiKeyService: IApiKeyService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<ApiKeysScreenModel.ApiKeysState>(ApiKeysState()), Refreshable {

    private val refresher = RefreshCoalescer(screenModelScope, dispatchers.io) { fetchKeys() }
    override val isRefreshing = refresher.isRefreshing

    override fun refresh() {
        refresher.refresh()
    }

    data class ApiKeysState(
        val keys: List<ApiKeyInfo> = emptyList(),
        val availableScopes: List<ApiKeyScopeInfo> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init {
        load()
    }

    fun load() {
        screenModelScope.launch(dispatchers.io) {
            fetchKeys()
        }
    }

    private suspend fun fetchKeys() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        try {
            rpcServiceManager.awaitAuthentication()
            val keys = apiKeyService.listApiKeys().sortedByDescending { it.createdAt }
            val scopes = apiKeyService.listAvailableScopes()
            mutableState.update { it.copy(keys = keys, availableScopes = scopes, isLoading = false) }
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
        }
    }

    suspend fun createKey(label: String, scopes: List<String>): String? {
        return try {
            val secret = apiKeyService.createApiKey(label, scopes)
            load()
            secret
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message) }
            null
        }
    }

    suspend fun getKeyString(id: PlatformUUID): String? {
        return try {
            apiKeyService.getApiKeyString(id)
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message) }
            null
        }
    }

    fun revokeKey(id: PlatformUUID) {
        screenModelScope.launch(dispatchers.io) {
            try {
                apiKeyService.revokeApiKey(id)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            load()
        }
    }
}
