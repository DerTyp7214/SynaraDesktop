package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.services.IUserService
import dev.dertyp.synara.rpc.RpcServiceManager

class UserServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IUserService {
    override suspend fun findUserById(id: PlatformUUID): User? {
        return manager.getService<IUserService>().findUserById(id)
    }

    override suspend fun findUserByUsername(username: String): User? {
        return manager.getService<IUserService>().findUserByUsername(username)
    }

    override suspend fun me(): User {
        return manager.getService<IUserService>().me()
    }

    override suspend fun getAllUsers(): List<User> {
        return manager.getService<IUserService>().getAllUsers()
    }

    override suspend fun setProfileImage(bytes: ByteArray) {
        manager.getService<IUserService>().setProfileImage(bytes)
    }

    override suspend fun setDisplayName(name: String?) {
        manager.getService<IUserService>().setDisplayName(name)
    }
}
