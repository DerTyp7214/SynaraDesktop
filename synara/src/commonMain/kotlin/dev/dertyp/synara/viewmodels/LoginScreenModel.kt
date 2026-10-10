package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IAuthService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.rpc.isTransportFailure
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import synara.synara.generated.resources.*

private val LOGIN_TAG = LogTag("login")

sealed class LoginResult {
    data object Idle : LoginResult()
    data object Loading : LoginResult()
    data object Success : LoginResult()
    data class Error(val message: StringResource) : LoginResult()
}

class LoginScreenModel(
    private val authService: IAuthService,
    private val rpcServiceManager: RpcServiceManager,
    dispatchers: SynaraDispatchers,
    private val logger: Logger
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LOGIN_TAG, "Login failed", e)
                _loginResult.value = LoginResult.Error(
                    if (e.isTransportFailure()) Res.string.error_server_unreachable else Res.string.login_failed
                )
            }
        }
    }

    fun backToSetup() {
        rpcServiceManager.resetToSetup()
    }

    fun reset() {
        _loginResult.value = LoginResult.Idle
    }
}
