package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class RemoteControlServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IRemoteControlService {
    override suspend fun reportStatus(status: RemotePlaybackStatus) {
        manager.getService<IRemoteControlService>().reportStatus(status)
    }

    override suspend fun getStatus(sessionId: PlatformUUID): RemotePlaybackStatus? {
        return manager.getService<IRemoteControlService>().getStatus(sessionId)
    }

    override fun observeStatus(sessionId: PlatformUUID): Flow<RemotePlaybackStatus> {
        return manager.getService<IRemoteControlService>().observeStatus(sessionId)
    }

    override suspend fun sendCommand(sessionId: PlatformUUID, command: PlaybackCommand): ClientRequestStatus {
        return manager.getService<IRemoteControlService>().sendCommand(sessionId, command)
    }
}
