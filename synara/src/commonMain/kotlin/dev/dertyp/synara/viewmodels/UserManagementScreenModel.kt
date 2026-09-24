package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.services.IUserService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UserManagementScreenModel(
    private val userService: IUserService,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<UserManagementScreenModel.UserManagementState>(UserManagementState()), Refreshable {

    private val refresher = RefreshCoalescer(screenModelScope, dispatchers.io) { fetchUsers() }
    override val isRefreshing = refresher.isRefreshing

    override fun refresh() {
        refresher.refresh()
    }

    data class UserManagementState(
        val users: List<User> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val createError: String? = null
    )

    init {
        loadUsers()
    }

    fun loadUsers() {
        screenModelScope.launch(dispatchers.io) {
            fetchUsers()
        }
    }

    private suspend fun fetchUsers() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        try {
            rpcServiceManager.awaitAuthentication()
            val users = userService.getAllUsers().sortedBy { it.username.lowercase() }
            mutableState.update { it.copy(users = users, isLoading = false) }
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
        }
    }

    fun setCapabilities(userId: PlatformUUID, capabilities: List<UserCapability>) {
        screenModelScope.launch(dispatchers.io) {
            try {
                userService.setCapabilities(userId, capabilities)
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
            loadUsers()
        }
    }

    suspend fun createUser(
        username: String,
        password: String,
        isAdmin: Boolean,
        capabilities: List<UserCapability>
    ): Boolean {
        return try {
            mutableState.update { it.copy(createError = null) }
            val user = userService.createUser(AuthenticationRequest(username, password), isAdmin, capabilities)
            if (user == null) {
                mutableState.update { it.copy(createError = "Failed to create user") }
                false
            } else {
                loadUsers()
                true
            }
        } catch (e: Exception) {
            mutableState.update { it.copy(createError = e.message ?: "Unknown error") }
            false
        }
    }
}
