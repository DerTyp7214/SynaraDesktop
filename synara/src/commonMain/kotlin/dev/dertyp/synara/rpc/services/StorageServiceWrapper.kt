package dev.dertyp.synara.rpc.services

import dev.dertyp.services.IStorageService
import dev.dertyp.synara.rpc.RpcServiceManager

class StorageServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IStorageService {
    override suspend fun getTotalStorage(): Long {
        return manager.getService<IStorageService>().getTotalStorage()
    }
}
