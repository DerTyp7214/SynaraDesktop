package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import dev.dertyp.data.ServerValidationResult
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TestConnectionResult {
    data object Idle : TestConnectionResult()
    data object Loading : TestConnectionResult()
    data class Success(val message: String) : TestConnectionResult()
    data class Error(val message: String) : TestConnectionResult()
}

class SetupScreenModel(
    private val rpcServiceManager: RpcServiceManager
) : ScreenModel {
    private val _testConnectionResult = MutableStateFlow<TestConnectionResult>(TestConnectionResult.Idle)
    val testConnectionResult = _testConnectionResult.asStateFlow()

    fun resetTestConnectionResult() {
        _testConnectionResult.value = TestConnectionResult.Idle
    }

    suspend fun testConnection(host: String, port: Int, path: String = "/") {
        _testConnectionResult.value = TestConnectionResult.Loading
        try {
            val result = rpcServiceManager.validateServer(host, port, path)
            if (result.validated) {
                _testConnectionResult.value = TestConnectionResult.Success("Connection successful! Server is healthy.")
                lastHost = host
                lastPort = port
                lastValidationResult = result
                lastPath = path
            } else {
                _testConnectionResult.value = TestConnectionResult.Error("Connection failed: Server is unreachable or unhealthy.")
            }
        } catch (e: Exception) {
            _testConnectionResult.value = TestConnectionResult.Error("Connection failed: ${e.message}")
        }
    }

    private var lastHost: String? = null
    private var lastPort: Int? = null
    private var lastValidationResult: ServerValidationResult? = null
    private var lastPath: String = "/"

    fun setServer() {
        val host = lastHost ?: return
        val port = lastPort ?: return
        val result = lastValidationResult ?: return

        rpcServiceManager.setServer(
            host = host,
            port = port,
            ssl = result.useSsl,
            path = lastPath
        )
    }

    fun getHost(): String? = rpcServiceManager.host
    fun getPort(): Int? = rpcServiceManager.port
    fun getRpcPath(): String = rpcServiceManager.rpcPath
}
