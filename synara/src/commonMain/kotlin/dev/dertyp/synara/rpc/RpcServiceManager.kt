package dev.dertyp.synara.rpc

import com.russhwolf.settings.Settings
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.ioDispatcher
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.services.IServerStatsService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.Config
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.toEpochMilliseconds
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.withService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.component.KoinComponent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass
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

    private var isUsingFallback = false
    private var hasFetchedProxyInfo = false

    private val authUpdates = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            Config.isProxyEnabled.collect {
                isUsingFallback = false
                clear()
            }
        }
        scope.launch {
            authUpdates.collect {
                if (isAuthenticated()) {
                    try {
                        getAuthenticatedClient()
                        onServerReachable()

                        if (!hasFetchedProxyInfo) {
                            hasFetchedProxyInfo = true
                            launch { fetchProxyInfo() }
                        }
                    } catch (_: Exception) {
                        onServerUnreachable()
                    }
                }
            }
        }
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

    val tokenExpiration: Long?
        get() = storedTokenExpiration

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
        val base = if (h != null && p != null) "ws://$h:$p" else null

        val ph = settings.getOrNull(SettingKey.ProxyHost)
        val pp = settings.getOrNull(SettingKey.ProxyPort)
        val pi = settings.getOrNull(SettingKey.ProxyId)
        val ps = settings.get(SettingKey.ProxySsl, false)
        val proxy = if (ph != null && pp != null) {
            val scheme = if (ps) "wss://" else "ws://"
            "$scheme$ph:$pp${pi?.let { "/$it" } ?: ""}"
        } else null

        val preferProxy = settings.get(SettingKey.IsProxyEnabled, false)
        val useProxy = isUsingFallback || preferProxy

        return if (useProxy && proxy != null) proxy else base
    }

    override fun onServerUnreachable() {
        isUsingFallback = !isUsingFallback
        super.onServerUnreachable()
    }

    private suspend fun fetchProxyInfo() {
        try {
            val statsService = getService<IServerStatsService>()
            val info = statsService.getProxyInfo()
            if (info != null) {
                Config.setProxyHost(info.host)
                Config.setProxyPort(info.controlPort)
                Config.setProxyId(info.id)
                Config.setProxySsl(info.ssl)
            }
        } catch (_: Exception) {
        }
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
        authUpdates.emit(Unit)
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
        hasFetchedProxyInfo = false
        scope.launch {
            clearAuth()
            clear()
            refreshConnectionState()
        }
    }

    fun clearServerConfig() {
        this.host = null
        this.port = null
        hasFetchedProxyInfo = false
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

    fun preloadServices(vararg services: KClass<out Any>) {
        scope.launch {
            if (!isAuthenticated()) return@launch
            services.forEach {
                try {
                    getService(it)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun retryConnection() {
        scope.launch {
            val h = host
            val p = port
            val base = if (h != null && p != null) "ws://$h:$p" else null

            val ph = settings.getOrNull(SettingKey.ProxyHost)
            val pp = settings.getOrNull(SettingKey.ProxyPort)
            val pi = settings.getOrNull(SettingKey.ProxyId)
            val ps = settings.get(SettingKey.ProxySsl, false)
            val proxy = if (ph != null && pp != null) {
                val scheme = if (ps) "wss://" else "ws://"
                "$scheme$ph:$pp${pi?.let { "/$it" } ?: ""}"
            } else null

            val preferProxy = settings.get(SettingKey.IsProxyEnabled, false)
            val primary = if (preferProxy) proxy else base
            val secondary = if (preferProxy) base else proxy

            if (primary != null && validateServer(primary)) {
                isUsingFallback = false
                onServerReachable()
                if (!hasFetchedProxyInfo) {
                    hasFetchedProxyInfo = true
                    launch { fetchProxyInfo() }
                }
                clear()
                return@launch
            }

            if (secondary != null && validateServer(secondary)) {
                isUsingFallback = true
                onServerReachable()
                if (!hasFetchedProxyInfo) {
                    hasFetchedProxyInfo = true
                    launch { fetchProxyInfo() }
                }
                clear()
                return@launch
            }

            onServerUnreachable()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <@Rpc T : Any> getService(serviceClass: KClass<T>): T {
        return synchronized(serviceCache) {
            serviceCache.getOrPut(serviceClass) {
                transparentClient.withService(serviceClass)
            } as T
        }
    }
}
