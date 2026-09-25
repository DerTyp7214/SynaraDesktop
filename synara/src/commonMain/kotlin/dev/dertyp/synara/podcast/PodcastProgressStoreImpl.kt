package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val PODCAST_PROGRESS_TAG = LogTag("podcast-progress")

class PodcastProgressStoreImpl(
    private val podcastService: IPodcastService,
    private val rpcServiceManager: RpcServiceManager,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) : PodcastProgressStore {
    companion object {
        private val RETRY_DELAY = 5.seconds
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())

    private val _latest = MutableStateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>(emptyMap())
    override val latest: StateFlow<Map<PlatformUUID, PodcastEpisodeProgress>> = _latest.asStateFlow()

    private val _updates = MutableSharedFlow<PodcastEpisodeProgress>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val updates: SharedFlow<PodcastEpisodeProgress> = _updates.asSharedFlow()

    init {
        scope.launch {
            combine(
                rpcServiceManager.isServerReachable,
                rpcServiceManager.connectionState,
                Config.isPodcastsEnabled
            ) { reachable, connection, enabled ->
                reachable && enabled && connection == RpcServiceManager.ConnectionState.Authenticated
            }.distinctUntilChanged().collectLatest { active ->
                if (active) observeLoop()
            }
        }
    }

    override fun record(progress: PodcastEpisodeProgress) {
        var changed = false
        _latest.update { map ->
            val existing = map[progress.episodeId]
            if (existing != null && existing.updatedAt >= progress.updatedAt) {
                changed = false
                map
            } else {
                changed = true
                map + (progress.episodeId to progress)
            }
        }
        if (changed) _updates.tryEmit(progress)
    }

    private suspend fun observeLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching {
                podcastService.observeProgress().collect { record(it) }
            }.onFailure {
                if (it is CancellationException) throw it
                logger.error(PODCAST_PROGRESS_TAG, it.message ?: "Progress subscription failed", it)
            }
            delay(RETRY_DELAY)
        }
    }
}
