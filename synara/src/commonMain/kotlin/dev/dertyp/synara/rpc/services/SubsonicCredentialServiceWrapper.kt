package dev.dertyp.synara.rpc.services

import dev.dertyp.data.SubsonicCredentialInfo
import dev.dertyp.services.ISubsonicCredentialService
import dev.dertyp.synara.rpc.RpcServiceManager

class SubsonicCredentialServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ISubsonicCredentialService {
    override suspend fun getSubsonicCredential(): SubsonicCredentialInfo? {
        return manager.getService<ISubsonicCredentialService>().getSubsonicCredential()
    }

    override suspend fun regenerateSubsonicCredential(): SubsonicCredentialInfo {
        return manager.getService<ISubsonicCredentialService>().regenerateSubsonicCredential()
    }

    override suspend fun revokeSubsonicCredential(): Boolean {
        return manager.getService<ISubsonicCredentialService>().revokeSubsonicCredential()
    }
}
