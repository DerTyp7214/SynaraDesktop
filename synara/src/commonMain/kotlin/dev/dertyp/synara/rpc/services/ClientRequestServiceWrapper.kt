package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ClientRequestServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IClientRequestService {
    override fun observeRequests(): Flow<ClientRequest> {
        return manager.getService<IClientRequestService>().observeRequests()
    }

    override suspend fun complete(requestId: PlatformUUID, status: ClientRequestStatus) {
        manager.getService<IClientRequestService>().complete(requestId, status)
    }
}
