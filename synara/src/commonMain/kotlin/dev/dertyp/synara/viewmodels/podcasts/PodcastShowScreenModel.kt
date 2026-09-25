package dev.dertyp.synara.viewmodels.podcasts

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.*
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.RefreshCoalescer
import dev.dertyp.synara.viewmodels.Refreshable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class PodcastShowState(
    val show: PodcastShow? = null,
    val isLoading: Boolean = false,
    val notFound: Boolean = false,
    val failed: Boolean = false,
    val continuation: PodcastContinuation? = null,
    val continuationLoaded: Boolean = false,
    val newestFirst: Boolean = true,
    val isUpdatingSubscription: Boolean = false
)

class PodcastShowScreenModel(
    private val showId: PlatformUUID,
    private val rpcServiceManager: RpcServiceManager,
    private val podcastService: IPodcastService,
    private val progressStore: PodcastProgressStore,
    val podcastPlayer: PodcastPlayer,
    dispatchers: SynaraDispatchers
) : StateScreenModel<PodcastShowState>(PodcastShowState()), Refreshable {

    private val modelDispatcher = dispatchers.createNamed("PodcastShowScreenModel")
    private val mainDispatcher = dispatchers.main

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { refreshFromFeed() }
    override val isRefreshing = refresher.isRefreshing

    private val _refreshFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshFailed = _refreshFailed.asSharedFlow()

    val episodes = EpisodeListPager(screenModelScope, modelDispatcher, progressStore) { page, size ->
        podcastService.getEpisodes(showId, page, size, state.value.newestFirst)
    }

    private var continuationJob: Job? = null
    private var authenticated = false

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            authenticated = true
            mutableState.update { it.copy(isLoading = true) }
            loadShow()
            if (state.value.show != null) {
                episodes.reload()
                loadContinuation()
            }
        }

        screenModelScope.launch(modelDispatcher) {
            progressStore.updates.collect { progress ->
                if (progress.showId != showId) return@collect
                episodes.applyProgress(progress)
                scheduleContinuation()
            }
        }
    }

    private suspend fun loadShow() {
        try {
            val show = podcastService.getShow(showId)
            mutableState.update {
                it.copy(show = show ?: it.show, notFound = show == null, failed = false, isLoading = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(isLoading = false, failed = it.show == null) }
        }
    }

    private suspend fun loadContinuation() {
        try {
            val continuation = podcastService.resolveContinuation(showId)
            mutableState.update { it.copy(continuation = continuation, continuationLoaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(continuationLoaded = true) }
        }
    }

    private fun scheduleContinuation() {
        continuationJob?.cancel()
        continuationJob = screenModelScope.launch(modelDispatcher) {
            delay(CONTINUATION_DEBOUNCE)
            loadContinuation()
        }
    }

    private suspend fun refreshFromFeed() {
        if (!authenticated) return
        try {
            val show = podcastService.refreshShow(showId)
            mutableState.update { it.copy(show = show, notFound = false, failed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _refreshFailed.tryEmit(Unit)
            loadShow()
        }
        if (state.value.show != null) {
            episodes.reload()
            loadContinuation()
        }
    }

    override fun refresh() {
        refresher.refresh()
    }

    fun retry() {
        screenModelScope.launch(modelDispatcher) {
            mutableState.update { it.copy(isLoading = true, failed = false) }
            loadShow()
            if (state.value.show != null) {
                episodes.reload()
                loadContinuation()
            }
        }
    }

    fun setNewestFirst(newestFirst: Boolean) {
        if (state.value.newestFirst == newestFirst) return
        mutableState.update { it.copy(newestFirst = newestFirst) }
        episodes.load()
    }

    fun playContinuation() {
        val continuation = state.value.continuation
        if (continuation == null) {
            podcastPlayer.playShow(showId)
            return
        }
        screenModelScope.launch(modelDispatcher) {
            val queue = try {
                podcastService.continuationQueue(continuation.episode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                listOf(continuation.episode)
            }
            val synced = queue.withProgress(progressStore.latest.value)
            withContext(mainDispatcher) { podcastPlayer.playEpisodes(synced, 0) }
        }
    }

    fun playFromList(index: Int) {
        val list = episodes.state.value.episodes
        val target = list.getOrNull(index) ?: return
        if (podcastPlayer.currentEpisode.value?.id == target.id) {
            podcastPlayer.togglePlayPause()
            return
        }
        val queue = if (state.value.newestFirst) {
            list.subList(0, index + 1).reversed()
        } else {
            list.subList(index, list.size)
        }
        podcastPlayer.playEpisodes(queue, 0)
    }

    suspend fun subscribe() {
        mutableState.update { it.copy(isUpdatingSubscription = true) }
        try {
            val show = podcastService.subscribeToShow(showId)
            mutableState.update { it.copy(show = show) }
        } finally {
            mutableState.update { it.copy(isUpdatingSubscription = false) }
        }
    }

    suspend fun unsubscribe() {
        mutableState.update { it.copy(isUpdatingSubscription = true) }
        try {
            podcastService.unsubscribe(showId)
            val show = podcastService.getShow(showId)
            mutableState.update { state ->
                state.copy(show = show ?: state.show?.copy(subscribed = false))
            }
        } finally {
            mutableState.update { it.copy(isUpdatingSubscription = false) }
        }
    }

    fun onShowChanged(show: PodcastShow) {
        if (show.id != showId) return
        val previous = state.value.show
        mutableState.update { it.copy(show = show, notFound = false) }
        val storageChanged = previous == null ||
            previous.deliveryMode != show.deliveryMode ||
            previous.keepEpisodes != show.keepEpisodes ||
            previous.retention != show.retention ||
            previous.lastFetchedAt != show.lastFetchedAt
        if (storageChanged) episodes.load()
    }

    fun onEpisodeChanged(episode: PodcastEpisode) {
        episodes.replace(episode)
    }

    suspend fun pollImports() {
        var lastPending: Set<PlatformUUID> = emptySet()
        var since: TimeMark? = null
        while (true) {
            delay(POLL_INTERVAL)
            val pending = episodes.state.value.episodes
                .filter { it.importState == PodcastImportState.QUEUED || it.importState == PodcastImportState.IMPORTING }
                .mapTo(HashSet()) { it.id }
            if (pending.isEmpty()) {
                lastPending = emptySet()
                since = null
                continue
            }
            if (!lastPending.containsAll(pending)) since = null
            lastPending = pending
            val mark = since ?: TimeSource.Monotonic.markNow().also { since = it }
            if (mark.elapsedNow() > POLL_WINDOW) continue
            withContext(modelDispatcher) {
                pending.forEach { id ->
                    try {
                        podcastService.getEpisode(id)?.let { episodes.replace(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        private val POLL_INTERVAL = 3.seconds
        private val POLL_WINDOW = 2.minutes
        private val CONTINUATION_DEBOUNCE = 1.seconds
    }
}
