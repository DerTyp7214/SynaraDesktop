package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ClientRequestServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IClientRequestService {
    override fun observeRequests(): Flow<ClientRequest> {
        return manager.getService<IClientRequestService>().observeRequests()
    }

    override fun connect(description: ClientDescription): Flow<ClientRequest> {
        return manager.getService<IClientRequestService>().connect(description)
    }

    override suspend fun complete(requestId: PlatformUUID, status: ClientRequestStatus) {
        manager.getService<IClientRequestService>().complete(requestId, status)
    }

    override suspend fun getOnlineDevices(): List<OnlineDevice> {
        return manager.getService<IClientRequestService>().getOnlineDevices()
    }
}
