package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ApiKeyInfo
import dev.dertyp.data.ApiKeyScopeInfo
import dev.dertyp.services.IApiKeyService
import dev.dertyp.synara.rpc.RpcServiceManager

class ApiKeyServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IApiKeyService {
    override suspend fun createApiKey(label: String, scopes: List<String>): String {
        return manager.getService<IApiKeyService>().createApiKey(label, scopes)
    }

    override suspend fun getApiKeyString(id: PlatformUUID): String? {
        return manager.getService<IApiKeyService>().getApiKeyString(id)
    }

    override suspend fun listAvailableScopes(): List<ApiKeyScopeInfo> {
        return manager.getService<IApiKeyService>().listAvailableScopes()
    }

    override suspend fun listApiKeys(): List<ApiKeyInfo> {
        return manager.getService<IApiKeyService>().listApiKeys()
    }

    override suspend fun revokeApiKey(id: PlatformUUID): Boolean {
        return manager.getService<IApiKeyService>().revokeApiKey(id)
    }
}
