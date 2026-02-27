package dev.dertyp.synara.rpc

import com.russhwolf.settings.Settings
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.ioDispatcher
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.toEpochMilliseconds
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

class RpcServiceManager(
    client: HttpClient,
    private val settings: Settings
) : BaseRpcServiceManager(client, CoroutineScope(ioDispatcher + SupervisorJob())), KoinComponent {

    enum class ConnectionState {
        Loading,
        SetupRequired,
        LoginRequired,
        Authenticated
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    init {
        scope.launch {
            refreshConnectionState()
        }
    }

    var host: String?
        get() = settings.getOrNull(SettingKey.Host)
        private set(value) = settings.put(SettingKey.Host, value)

    var port: Int?
        get() = settings.getOrNull(SettingKey.Port)
        private set(value) = settings.put(SettingKey.Port, value)

    private var storedAuthToken: String?
        get() = settings.getOrNull(SettingKey.AuthToken)
        set(value) = settings.put(SettingKey.AuthToken, value)

    private var storedRefreshToken: String?
        get() = settings.getOrNull(SettingKey.RefreshToken)
        set(value) = settings.put(SettingKey.RefreshToken, value)

    private var storedTokenExpiration: Long?
        get() = settings.getOrNull(SettingKey.TokenExpiration)
        set(value) = settings.put(SettingKey.TokenExpiration, value)

    override suspend fun getRpcUrl(): String? {
        val h = host
        val p = port
        return if (h != null && p != null) "ws://$h:$p" else null
    }

    override fun getAuthToken(): String? = storedAuthToken

    override fun getRefreshToken(): String? = storedRefreshToken

    override fun isTokenExpired(): Boolean {
        return storedTokenExpiration?.let { it <= System.currentTimeMillis() } ?: true
    }

    override fun isAuthenticated(): Boolean = getAuthToken() != null

    public override suspend fun updateAuth(response: AuthenticationResponse) {
        storedAuthToken = response.token
        storedRefreshToken = response.refreshToken
        storedTokenExpiration = response.expiresAt.toEpochMilliseconds()
        refreshConnectionState()
    }

    public override suspend fun handleAuthFailure() {
        clearAuth()
        clear()
        refreshConnectionState()
    }

    private fun clearAuth() {
        storedAuthToken = null
        storedRefreshToken = null
        storedTokenExpiration = null
    }

    fun setServer(host: String, port: Int) {
        this.host = host
        this.port = port
        scope.launch {
            clearAuth()
            clear()
            refreshConnectionState()
        }
    }

    fun clearServerConfig() {
        this.host = null
        this.port = null
        scope.launch {
            clearAuth()
            clear()
            refreshConnectionState()
        }
    }

    suspend fun refreshConnectionState() {
        _connectionState.value = checkConnectionState()
    }

    private suspend fun checkConnectionState(): ConnectionState {
        getRpcUrl() ?: return ConnectionState.SetupRequired

        if (!isAuthenticated() && getRefreshToken() == null) return ConnectionState.LoginRequired

        return try {
            getAuthenticatedClient()
            ConnectionState.Authenticated
        } catch (_: Exception) {
            ConnectionState.LoginRequired
        }
    }
}
