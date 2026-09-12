package dev.dertyp.synara.rpc

import com.russhwolf.settings.Settings
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.ioDispatcher
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.rpc.BaseRpcServiceManager
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
import org.koin.core.component.inject
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

    enum class AuthFailureReason {
        RefreshRejected,
        Other
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _authFailureReason = MutableStateFlow<AuthFailureReason?>(null)
    val authFailureReason: StateFlow<AuthFailureReason?> = _authFailureReason.asStateFlow()

    private val logger: Logger by inject()

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
            if (!hasFetchedProxyInfo) {
                hasFetchedProxyInfo = true
                launch { fetchProxyInfo() }
            }
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
            connectionState.collect { state ->
                if (state == ConnectionState.Authenticated) ensureHandshake()
            }
        }
        scope.launch {
            refreshConnectionState()
        }
    }

    private suspend fun ensureHandshake() {
        if (handshake.value != null) return
        if (fetchHandshake() == null) {
            logger.warning(LogTag.RPC, "Failed to fetch handshake, server UI stays unavailable")
        }
    }

    var host: String?
        get() = settings.getOrNull(SettingKey.Host)
        private set(value) = settings.put(SettingKey.Host, value)

    var port: Int?
        get() = settings.getOrNull(SettingKey.Port)
        private set(value) = settings.put(SettingKey.Port, value)

    var ssl: Boolean
        get() = settings.get(SettingKey.Ssl, false)
        private set(value) = settings.put(SettingKey.Ssl, value)

    var rpcPath: String
        get() = settings.get(SettingKey.RpcPath, "/")
        private set(value) = settings.put(SettingKey.RpcPath, value)

    override val sslConfirmed: Boolean
        get() = settings.get(SettingKey.SslConfirmed, false)

    override suspend fun setSslConfirmed(value: Boolean) {
        settings.put(SettingKey.SslConfirmed, value)
    }

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
    private fun tokenClaim(vararg names: String): String? {
        val token = storedAuthToken ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(parts[1]).decodeToString()
            val json = Json.parseToJsonElement(payload).jsonObject
            names.firstNotNullOfOrNull { json[it]?.jsonPrimitive?.content }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    val sessionId: String?
        get() = tokenClaim("ses")

    val userId: String?
        get() = tokenClaim("sub", "usr")

    override suspend fun getRpcUrl(): String? {
        val sslAllowed = sessionSslOverride.value != false
        val h = host
        val p = port
        val s = ssl && sslAllowed
        val path = rpcPath.removeSuffix("/")
        val base = if (h != null && p != null) {
            val scheme = if (s) "wss://" else "ws://"
            "$scheme$h:$p$path"
        } else null

        val ph = settings.getOrNull(SettingKey.ProxyHost)
        val pp = settings.getOrNull(SettingKey.ProxyPort)
        val pi = settings.getOrNull(SettingKey.ProxyId)
        val ps = settings.get(SettingKey.ProxySsl, false) && sslAllowed
        val proxy = if (ph != null && pp != null) {
            val scheme = if (ps) "wss://" else "ws://"
            "$scheme$ph:$pp${pi?.let { "/$it" } ?: ""}"
        } else null

        val preferProxy = settings.get(SettingKey.IsProxyEnabled, false)
        val useProxy = isUsingFallback || preferProxy

        return if (useProxy && proxy != null) proxy else base
    }

    override suspend fun setRpcUrl(host: String, port: Int, ssl: Boolean, path: String) {
        this.host = host
        this.port = port
        this.ssl = ssl
        this.rpcPath = path
        setSslConfirmed(false)
    }

    override fun uiLocale(): String? = Config.language.value

    override fun onServerUnreachable() {
        isUsingFallback = !isUsingFallback
        super.onServerUnreachable()
    }

    private suspend fun fetchProxyInfo() {
        try {
            val statsService = getServerStatsService()
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

    public override suspend fun handleAuthFailure(reason: Throwable?) {
        _authFailureReason.value = if (reason != null && isRefreshRejected(reason)) {
            AuthFailureReason.RefreshRejected
        } else {
            AuthFailureReason.Other
        }
        clearAuth()
        clear()
        _connectionState.value = ConnectionState.LoginRequired
    }

    fun clearAuthFailureReason() {
        _authFailureReason.value = null
    }

    fun logout() {
        scope.launch {
            clearAuth()
            clear()
            refreshConnectionState()
        }
    }

    fun clearAuth() {
        storedAuthToken = null
        storedRefreshToken = null
        storedTokenExpiration = null
    }

    fun setServer(host: String, port: Int, ssl: Boolean = false, path: String = "/") {
        this.host = host
        this.port = port
        this.ssl = ssl
        this.rpcPath = path
        hasFetchedProxyInfo = false
        resetSslSession()
        scope.launch {
            setSslConfirmed(false)
            clearAuth()
            clear()
            refreshConnectionState()
        }
    }

    fun resetToSetup() {
        hasFetchedProxyInfo = false
        resetHandshake()
        scope.launch {
            clearAuth()
            clear()
            _connectionState.value = ConnectionState.SetupRequired
        }
    }

    suspend fun refreshConnectionState() {
        _connectionState.value = checkConnectionState()
    }

    suspend fun awaitAuthentication() {
        connectionState.first { it == ConnectionState.Authenticated }
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

            val ph = settings.getOrNull(SettingKey.ProxyHost)
            val pp = settings.getOrNull(SettingKey.ProxyPort)
            val pi = settings.getOrNull(SettingKey.ProxyId)
            val ps = settings.get(SettingKey.ProxySsl, false)

            val preferProxy = settings.get(SettingKey.IsProxyEnabled, false)

            suspend fun tryPrimary(): Boolean {
                if (preferProxy) {
                    if (ph != null && pp != null) {
                        if (validateServer(ph, pp, pi ?: "/", ps).validated) {
                            isUsingFallback = false
                            return true
                        }
                    }
                } else {
                    if (h != null && p != null) {
                        if (validateServer(h, p, rpcPath, ssl).validated) {
                            isUsingFallback = false
                            return true
                        }
                    }
                }
                return false
            }

            suspend fun trySecondary(): Boolean {
                if (!preferProxy) {
                    if (ph != null && pp != null) {
                        if (validateServer(ph, pp, pi ?: "/", ps).validated) {
                            isUsingFallback = true
                            return true
                        }
                    }
                } else {
                    if (h != null && p != null) {
                        if (validateServer(h, p, rpcPath, ssl).validated) {
                            isUsingFallback = true
                            return true
                        }
                    }
                }
                return false
            }

            if (tryPrimary() || trySecondary()) {
                onServerReachable()
                if (!hasFetchedProxyInfo) {
                    hasFetchedProxyInfo = true
                    launch { fetchProxyInfo() }
                }
                clear()
                if (isAuthenticated()) ensureHandshake()
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
