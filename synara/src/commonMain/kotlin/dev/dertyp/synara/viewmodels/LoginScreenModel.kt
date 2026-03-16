package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.services.IAuthService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginResult {
    data object Idle : LoginResult()
    data object Loading : LoginResult()
    data object Success : LoginResult()
    data class Error(val message: String) : LoginResult()
}

class LoginScreenModel(
    private val authService: IAuthService,
    private val rpcServiceManager: RpcServiceManager,
    dispatchers: SynaraDispatchers
) : ScreenModel {
    private val modelDispatcher = dispatchers.createNamed("LoginScreenModel")

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }
    private val _loginResult = MutableStateFlow<LoginResult>(LoginResult.Idle)
    val loginResult = _loginResult.asStateFlow()

    fun login(username: String, password: String) {
        screenModelScope.launch(modelDispatcher) {
            _loginResult.value = LoginResult.Loading
            try {
                val response = authService.authenticate(username, password)
                rpcServiceManager.updateAuth(response)
                _loginResult.value = LoginResult.Success
            } catch (e: Exception) {
                _loginResult.value = LoginResult.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun clearServer() {
        rpcServiceManager.clearServerConfig()
    }

    fun reset() {
        _loginResult.value = LoginResult.Idle
    }
}
