package dev.dertyp.synara.rpc.services

import dev.dertyp.data.ListenBackupConfig
import dev.dertyp.data.ListenBackupConnectionTest
import dev.dertyp.data.ListenBackupState
import dev.dertyp.services.IListenBackupService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ListenBackupServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IListenBackupService {
    override suspend fun getState(): ListenBackupState {
        return manager.getService<IListenBackupService>().getState()
    }

    override fun getStateFlow(): Flow<ListenBackupState> {
        return manager.getService<IListenBackupService>().getStateFlow()
    }

    override suspend fun updateConfig(config: ListenBackupConfig): ListenBackupState {
        return manager.getService<IListenBackupService>().updateConfig(config)
    }

    override suspend fun testConnection(config: ListenBackupConfig?): ListenBackupConnectionTest {
        return manager.getService<IListenBackupService>().testConnection(config)
    }

    override suspend fun syncNow(): ListenBackupState {
        return manager.getService<IListenBackupService>().syncNow()
    }

    override suspend fun resetCursor(): ListenBackupState {
        return manager.getService<IListenBackupService>().resetCursor()
    }
}
