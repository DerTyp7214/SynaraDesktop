package dev.dertyp.synara.rpc.services

import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.services.IAuthService
import dev.dertyp.synara.rpc.RpcServiceManager

class AuthServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IAuthService {
    override suspend fun authenticate(username: String, password: String): AuthenticationResponse {
        return manager.getAuthService().authenticate(username, password)
    }

    override suspend fun refreshToken(refreshToken: String): AuthenticationResponse {
        return manager.getAuthService().refreshToken(refreshToken)
    }
}
