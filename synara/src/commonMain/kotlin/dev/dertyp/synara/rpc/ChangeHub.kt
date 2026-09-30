package dev.dertyp.synara.rpc

import dev.dertyp.data.ChangeTopic
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IChangeService
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val CHANGE_HUB_TAG = LogTag("changeHub")

class ChangeHub(
    private val changeService: IChangeService,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    private val dispatchers: SynaraDispatchers
) {
    companion object {
        private val STREAM_RETRY_DELAY = 5.seconds
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var started = false

    private val _changes = MutableSharedFlow<ChangeTopic>(extraBufferCapacity = 16)

    fun start() {
        if (started) return
        started = true

        scope.launch {
            combine(
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState
            ) { reachable, connection ->
                reachable && connection == RpcServiceManager.ConnectionState.Authenticated
            }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                connectLoop()
            }
        }
    }

    fun topic(topic: ChangeTopic): Flow<Unit> {
        return _changes.filter { it == topic }.map { }.onStart { emit(Unit) }
    }

    suspend fun refreshOn(topic: ChangeTopic, block: suspend () -> Unit) {
        topic(topic).collect {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report(e)
            }
        }
    }

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching {
                changeService.observeChanges()
                    .onStart { tickAllTopics() }
                    .collect { change ->
                        if (change.topic != ChangeTopic.UNKNOWN) _changes.emit(change.topic)
                    }
            }.onFailure {
                if (it is CancellationException) throw it
                report(it)
            }
            delay(STREAM_RETRY_DELAY)
        }
    }

    private suspend fun tickAllTopics() {
        ChangeTopic.entries.forEach { topic ->
            if (topic != ChangeTopic.UNKNOWN) _changes.emit(topic)
        }
    }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        logger.error(CHANGE_HUB_TAG, error.message ?: "Change stream failed", error)
    }
}
