package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.services.IRadioService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class RadioServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IRadioService {
    override suspend fun createRadioSession(type: RadioType, seed: RadioSeed?): PlatformUUID {
        return manager.getService<IRadioService>().createRadioSession(type, seed)
    }

    override fun radioFlow(sessionId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<IRadioService>().radioFlow(sessionId)
    }
}
