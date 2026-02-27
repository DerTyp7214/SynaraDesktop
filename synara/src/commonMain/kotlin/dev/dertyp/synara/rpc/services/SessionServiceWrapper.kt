package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Session
import dev.dertyp.services.ISessionService
import dev.dertyp.synara.rpc.RpcServiceManager

class SessionServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ISessionService {
    override suspend fun deactivateSession(sessionId: PlatformUUID) {
        manager.getService<ISessionService>().deactivateSession(sessionId)
    }

    override suspend fun getSessions(): List<Session> {
        return manager.getService<ISessionService>().getSessions()
    }
}
