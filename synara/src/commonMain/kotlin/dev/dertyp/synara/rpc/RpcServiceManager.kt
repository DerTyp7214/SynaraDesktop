package dev.dertyp.synara.rpc

import com.russhwolf.settings.Settings
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.ioDispatcher
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.services.IUserService
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.toEpochMilliseconds
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.component.KoinComponent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.minutes

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

    @OptIn(ExperimentalEncodingApi::class)
    val sessionId: String?
        get() {
            val token = storedAuthToken ?: return null
            return try {
                val parts = token.split(".")
                if (parts.size < 2) return null
                val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(parts[1]).decodeToString()
                val json = Json.parseToJsonElement(payload).jsonObject
                json["ses"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    override suspend fun getRpcUrl(): String? {
        val h = host
        val p = port
        return if (h != null && p != null) "ws://$h:$p" else null
    }

    public override fun getAuthToken(): String? = storedAuthToken

    override fun getRefreshToken(): String? = storedRefreshToken

    override fun isTokenExpired(): Boolean {
        return storedTokenExpiration?.let { it <= System.currentTimeMillis() + 5.minutes.inWholeMilliseconds }
            ?: true
    }

    override fun isAuthenticated(): Boolean = getAuthToken() != null

    public override suspend fun updateAuth(response: AuthenticationResponse) {
        storedAuthToken = response.token
        storedRefreshToken = response.refreshToken
        storedTokenExpiration = response.expiresAt.toEpochMilliseconds()

        _connectionState.value = ConnectionState.Authenticated
    }

    public override suspend fun handleAuthFailure() {
        clearAuth()
        clear()
        _connectionState.value = ConnectionState.LoginRequired
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

    suspend fun awaitAuthentication() {
        connectionState.filter { it == ConnectionState.Authenticated }.first()
    }

    private suspend fun checkConnectionState(): ConnectionState {
        getRpcUrl() ?: return ConnectionState.SetupRequired

        if (!isAuthenticated() && getRefreshToken() == null) return ConnectionState.LoginRequired

        return try {
            getService<IUserService>().me()
            ConnectionState.Authenticated
        } catch (_: Exception) {
            if (!isAuthenticated() && getRefreshToken() == null) {
                ConnectionState.LoginRequired
            } else {
                ConnectionState.Authenticated
            }
        }
    }
}
