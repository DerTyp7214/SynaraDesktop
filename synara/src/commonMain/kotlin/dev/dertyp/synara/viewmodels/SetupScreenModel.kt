package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
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

    suspend fun testConnection(host: String, port: Int) {
        _testConnectionResult.value = TestConnectionResult.Loading
        try {
            val baseUrl = "ws://$host:$port"
            val isValid = rpcServiceManager.validateServer(baseUrl)
            if (isValid) {
                _testConnectionResult.value = TestConnectionResult.Success("Connection successful! Server is healthy.")
            } else {
                _testConnectionResult.value = TestConnectionResult.Error("Connection failed: Server is unreachable or unhealthy.")
            }
        } catch (e: Exception) {
            _testConnectionResult.value = TestConnectionResult.Error("Connection failed: ${e.message}")
        }
    }

    fun setServer(host: String, port: Int) {
        rpcServiceManager.setServer(host, port)
    }

    fun getHost(): String? = rpcServiceManager.host
    fun getPort(): Int? = rpcServiceManager.port
}
