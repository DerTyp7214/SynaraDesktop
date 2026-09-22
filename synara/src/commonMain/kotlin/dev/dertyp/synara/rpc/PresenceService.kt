package dev.dertyp.synara.rpc

import dev.dertyp.currentTimeMillis
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.OnlineDevice
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.Config
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val PRESENCE_TAG = LogTag("presence")

class PresenceService(
    private val clientRequestService: IClientRequestService,
    private val deviceIdentity: DeviceIdentity,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) {
    companion object {
        private val STREAM_RETRY_DELAY = 5.seconds
        private val DEVICE_POLL_INTERVAL = 15.seconds
        val CONTROLLED_WINDOW = 10.minutes
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var started = false

    private val _requests = MutableSharedFlow<ClientRequest>(extraBufferCapacity = 16)
    val requests: SharedFlow<ClientRequest> = _requests.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _onlineDevices = MutableStateFlow<List<OnlineDevice>>(emptyList())
    val onlineDevices: StateFlow<List<OnlineDevice>> = _onlineDevices.asStateFlow()

    private val _isControlled = MutableStateFlow(false)
    val isControlled: StateFlow<Boolean> = _isControlled.asStateFlow()

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var controlledUntil = 0L
    private var controlledJob: Job? = null

    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState,
                Config.isQueueSyncEnabled,
                Config.isRemoteControlEnabled,
                Config.queueSyncDeviceName
            ) { reachable, connection, queueSync, remoteControl, name ->
                Gate(
                    reachable = reachable,
                    authenticated = connection == RpcServiceManager.ConnectionState.Authenticated,
                    queueSync = queueSync,
                    remoteControl = remoteControl,
                    deviceName = name
                )
            }.distinctUntilChanged().collectLatest { gate ->
                if (!gate.isActive) {
                    _isConnected.value = false
                    _onlineDevices.value = emptyList()
                    return@collectLatest
                }
                try {
                    coroutineScope {
                        launch { pollLoop() }
                        launch { refreshes.collect { loadOnlineDevices() } }
                        connectLoop(description(gate))
                    }
                } finally {
                    _isConnected.value = false
                }
            }
        }
    }

    fun refreshOnlineDevices() {
        refreshes.tryEmit(Unit)
    }

    fun markControlled() {
        controlledUntil = currentTimeMillis() + CONTROLLED_WINDOW.inWholeMilliseconds
        _isControlled.value = true
        if (controlledJob?.isActive == true) return
        controlledJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                val remaining = controlledUntil - currentTimeMillis()
                if (remaining <= 0L) break
                delay(remaining)
            }
            _isControlled.value = false
        }
    }

    private data class Gate(
        val reachable: Boolean,
        val authenticated: Boolean,
        val queueSync: Boolean,
        val remoteControl: Boolean,
        val deviceName: String
    ) {
        val isActive: Boolean get() = reachable && authenticated
    }

    private fun description(gate: Gate): ClientDescription {
        val capabilities = mutableSetOf<ClientCapability>()
        if (gate.queueSync) capabilities += ClientCapability.QUEUE_SYNC
        if (gate.remoteControl) {
            capabilities += ClientCapability.REMOTE_CONTROL
            capabilities += ClientCapability.REMOTE_VOLUME
        }
        return ClientDescription(
            deviceName = deviceIdentity.deviceName(),
            platform = deviceIdentity.platform,
            deviceId = deviceIdentity.deviceId,
            capabilities = capabilities
        )
    }

    private suspend fun connectLoop(description: ClientDescription) {
        while (currentCoroutineContext().isActive) {
            runCatching {
                clientRequestService.connect(description)
                    .onStart {
                        _isConnected.value = true
                        refreshOnlineDevices()
                    }
                    .collect { request -> _requests.emit(request) }
            }.onFailure {
                if (it is CancellationException) throw it
                report(it)
            }
            _isConnected.value = false
            delay(STREAM_RETRY_DELAY)
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            loadOnlineDevices()
            delay(DEVICE_POLL_INTERVAL)
        }
    }

    private suspend fun loadOnlineDevices() {
        runCatching { clientRequestService.getOnlineDevices() }
            .onSuccess { _onlineDevices.value = it }
            .onFailure {
                if (it is CancellationException) throw it
                report(it)
            }
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(PRESENCE_TAG, error.message ?: "Presence failed", error)
    }
}
