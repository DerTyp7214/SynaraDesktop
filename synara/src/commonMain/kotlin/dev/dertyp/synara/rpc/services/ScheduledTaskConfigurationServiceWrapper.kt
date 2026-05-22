package dev.dertyp.synara.rpc.services

import dev.dertyp.data.TaskConfiguration
import dev.dertyp.services.IScheduledTaskConfigurationService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ScheduledTaskConfigurationServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager),
    IScheduledTaskConfigurationService {
    override suspend fun getConfigurations(): List<TaskConfiguration> {
        return manager.getService<IScheduledTaskConfigurationService>().getConfigurations()
    }

    override suspend fun updateConfiguration(configuration: TaskConfiguration) {
        manager.getService<IScheduledTaskConfigurationService>().updateConfiguration(configuration)
    }

    override fun getConfigurationsFlow(): Flow<List<TaskConfiguration>> {
        return manager.getService<IScheduledTaskConfigurationService>().getConfigurationsFlow()
    }
}
