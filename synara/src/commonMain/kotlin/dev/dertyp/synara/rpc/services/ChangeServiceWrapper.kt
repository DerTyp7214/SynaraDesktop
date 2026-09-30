package dev.dertyp.synara.rpc.services

import dev.dertyp.data.Change
import dev.dertyp.services.IChangeService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ChangeServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IChangeService {
    override fun observeChanges(): Flow<Change> {
        return manager.getService<IChangeService>().observeChanges()
    }
}
