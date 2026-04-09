package dev.dertyp.synara.rpc.services

import dev.dertyp.data.ScheduledTaskLog
import dev.dertyp.services.IScheduledTaskLogService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ScheduledTaskLogServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IScheduledTaskLogService {
    override suspend fun getGroupedLogs(): Map<String, List<ScheduledTaskLog>> {
        return manager.getService<IScheduledTaskLogService>().getGroupedLogs()
    }

    override fun getGroupedLogsFlow(): Flow<Map<String, List<ScheduledTaskLog>>> {
        return manager.getService<IScheduledTaskLogService>().getGroupedLogsFlow()
    }
}
