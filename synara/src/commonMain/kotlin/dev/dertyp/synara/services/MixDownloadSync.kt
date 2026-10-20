package dev.dertyp.synara.services

import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.mixes.fetch
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.hours

class MixDownloadSync(
    private val downloadManager: IDownloadManager,
    private val recommendationService: IRecommendationService,
    private val listeningStatsService: IListeningStatsService,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) {
    companion object {
        val REFRESH_INTERVAL = 6.hours
    }

    private val logTag = LogTag("download")
    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val refreshMutex = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            var previous: List<MixSpec>? = null
            Config.downloadedMixes.collect { mixes ->
                val prev = previous
                previous = mixes
                if (prev == null) return@collect
                val currentKeys = mixes.mapTo(HashSet()) { it.key }
                prev.map { it.key }.filter { it !in currentKeys }.forEach { downloadManager.removeMix(it) }
                mixes.filter { it !in prev }.forEach { scope.launch { refresh(it) } }
            }
        }

        scope.launch {
            combine(
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState
            ) { reachable, connection ->
                reachable && connection == RpcServiceManager.ConnectionState.Authenticated
            }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    Config.downloadedMixes.value.forEach { refresh(it) }
                    delay(REFRESH_INTERVAL)
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun refresh(spec: MixSpec) = refreshMutex.withLock {
        val songs = try {
            spec.fetch(recommendationService, listeningStatsService)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(logTag, "Failed to fetch a downloaded mix", e, data = "key=${spec.key}")
            return@withLock
        }
        if (Config.downloadedMixes.value.none { it.key == spec.key }) return@withLock
        downloadManager.downloadMix(spec.key, songs)
    }
}
