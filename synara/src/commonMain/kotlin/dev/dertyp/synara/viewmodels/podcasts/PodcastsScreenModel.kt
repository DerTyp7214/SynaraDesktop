package dev.dertyp.synara.viewmodels.podcasts

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.*
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.PodcastProgressStore
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.RefreshCoalescer
import dev.dertyp.synara.viewmodels.Refreshable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class PodcastsTab { SUBSCRIPTIONS, LATEST, IN_PROGRESS, BROWSE }

data class SubscriptionsState(
    val shows: List<PodcastShow> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false
)

data class BrowseShowsState(
    val query: String = "",
    val shows: List<PodcastShow> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false
) {
    val hasMore: Boolean get() = loaded && shows.size < total
}

sealed class DirectoryState {
    data object Idle : DirectoryState()
    data object Unavailable : DirectoryState()
    data object Loading : DirectoryState()
    data object Failed : DirectoryState()
    data class Results(val results: List<PodcastIndexResult>) : DirectoryState()
}

@OptIn(FlowPreview::class)
class PodcastsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val podcastService: IPodcastService,
    private val progressStore: PodcastProgressStore,
    val podcastPlayer: PodcastPlayer,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("PodcastsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { refreshCurrent() }
    override val isRefreshing = refresher.isRefreshing

    private val _tab = MutableStateFlow(PodcastsTab.SUBSCRIPTIONS)
    val tab = _tab.asStateFlow()

    private val _subscriptions = MutableStateFlow(SubscriptionsState())
    val subscriptions = _subscriptions.asStateFlow()

    val latest = EpisodeListPager(screenModelScope, modelDispatcher, progressStore) { page, size ->
        podcastService.getLatestEpisodes(page, size)
    }

    val inProgress = EpisodeListPager(screenModelScope, modelDispatcher, progressStore) { page, size ->
        podcastService.getInProgress(page, size)
    }

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _browse = MutableStateFlow(BrowseShowsState())
    val browse = _browse.asStateFlow()

    private val _directory = MutableStateFlow<DirectoryState>(DirectoryState.Idle)
    val directory = _directory.asStateFlow()

    private var indexes: List<PodcastIndexInfo>? = null
    private var browseGeneration = 0
    private var browseNextPage = 0
    private var browseJob: Job? = null
    private var authenticated = false

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            authenticated = true
            loadTab(_tab.value)
            _query
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                .collectLatest { if (_tab.value == PodcastsTab.BROWSE) search(it) }
        }

        screenModelScope.launch(modelDispatcher) {
            progressStore.updates.collect { progress ->
                latest.applyProgress(progress)
                inProgress.applyProgress(progress)
            }
        }
    }

    fun selectTab(tab: PodcastsTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        if (authenticated) screenModelScope.launch(modelDispatcher) { loadTab(tab) }
    }

    fun onShown() {
        if (!authenticated) return
        screenModelScope.launch(modelDispatcher) {
            when (_tab.value) {
                PodcastsTab.SUBSCRIPTIONS -> if (_subscriptions.value.loaded) loadSubscriptions()
                PodcastsTab.IN_PROGRESS -> if (inProgress.state.value.loaded) inProgress.reload()
                else -> Unit
            }
        }
    }

    private suspend fun loadTab(tab: PodcastsTab) {
        when (tab) {
            PodcastsTab.SUBSCRIPTIONS -> if (!_subscriptions.value.loaded) loadSubscriptions()
            PodcastsTab.LATEST -> latest.ensureLoaded()
            PodcastsTab.IN_PROGRESS -> inProgress.ensureLoaded()
            PodcastsTab.BROWSE -> {
                val trimmed = _query.value.trim()
                if (!_browse.value.loaded || _browse.value.query != trimmed) search(trimmed)
            }
        }
    }

    private suspend fun refreshCurrent() {
        if (!authenticated) return
        when (_tab.value) {
            PodcastsTab.SUBSCRIPTIONS -> loadSubscriptions()
            PodcastsTab.LATEST -> latest.reload()
            PodcastsTab.IN_PROGRESS -> inProgress.reload()
            PodcastsTab.BROWSE -> {
                indexes = null
                search(_query.value.trim())
            }
        }
    }

    override fun refresh() {
        refresher.refresh()
    }

    private suspend fun loadSubscriptions() {
        val hadContent = _subscriptions.value.loaded
        _subscriptions.update { it.copy(isLoading = !hadContent, failed = false) }
        try {
            val shows = podcastService.getSubscriptions()
            _subscriptions.value = SubscriptionsState(shows = shows, loaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _subscriptions.update { it.copy(isLoading = false, failed = !hadContent) }
        }
    }

    fun retry() {
        screenModelScope.launch(modelDispatcher) { refreshCurrent() }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    private suspend fun search(query: String) {
        browseJob?.cancel()
        val requestGeneration = ++browseGeneration
        browseNextPage = 0
        _browse.update {
            BrowseShowsState(
                query = query,
                shows = if (it.query == query) it.shows else emptyList(),
                total = if (it.query == query) it.total else 0,
                isLoading = true
            )
        }
        coroutineScope {
            val directoryJob = launch { searchDirectory(query, requestGeneration) }
            try {
                val response = podcastService.browseShows(query, 0, BROWSE_PAGE_SIZE)
                if (requestGeneration != browseGeneration) return@coroutineScope
                browseNextPage = 1
                _browse.value = BrowseShowsState(
                    query = query,
                    shows = response.data,
                    total = response.total,
                    loaded = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (requestGeneration == browseGeneration) {
                    _browse.value = BrowseShowsState(query = query, loaded = true, failed = true)
                }
            }
            directoryJob.join()
        }
    }

    private suspend fun searchDirectory(query: String, requestGeneration: Int) {
        if (query.length < MIN_DIRECTORY_QUERY) {
            _directory.value = DirectoryState.Idle
            return
        }
        val known = indexes ?: try {
            podcastService.getIndexes().also { indexes = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (known.none { it.configured }) {
            if (requestGeneration == browseGeneration) _directory.value = DirectoryState.Unavailable
            return
        }
        _directory.value = DirectoryState.Loading
        try {
            val results = podcastService.searchIndex(query, DIRECTORY_LIMIT)
            if (requestGeneration == browseGeneration) _directory.value = DirectoryState.Results(results)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (requestGeneration == browseGeneration) _directory.value = DirectoryState.Failed
        }
    }

    fun loadMoreShows() {
        val current = _browse.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        val requestGeneration = browseGeneration
        val page = browseNextPage
        _browse.update { it.copy(isLoadingMore = true) }
        browseJob = screenModelScope.launch(modelDispatcher) {
            try {
                val response = podcastService.browseShows(current.query, page, BROWSE_PAGE_SIZE)
                if (requestGeneration != browseGeneration) return@launch
                browseNextPage = page + 1
                _browse.update { state ->
                    val known = state.shows.mapTo(HashSet()) { it.id }
                    state.copy(
                        shows = state.shows + response.data.filter { it.id !in known },
                        total = if (response.data.isEmpty()) state.shows.size else response.total,
                        isLoadingMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (requestGeneration == browseGeneration) _browse.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun playEpisodes(episodes: List<PodcastEpisode>, index: Int) {
        if (episodes.isEmpty()) return
        val target = episodes.getOrNull(index) ?: return
        val current = podcastPlayer.currentEpisode.value
        if (current?.id == target.id) {
            podcastPlayer.togglePlayPause()
        } else {
            podcastPlayer.playEpisodes(episodes, index)
        }
    }

    fun onEpisodeChanged(episode: PodcastEpisode) {
        latest.replace(episode)
        inProgress.replace(episode)
    }

    suspend fun subscribe(feedUrl: String): PodcastShow {
        val show = podcastService.subscribe(feedUrl)
        screenModelScope.launch(modelDispatcher) {
            if (_subscriptions.value.loaded) loadSubscriptions()
            if (latest.state.value.loaded) latest.reload()
        }
        return show
    }

    suspend fun scanLocal(): PodcastScanResult {
        val result = podcastService.scanLocal()
        screenModelScope.launch(modelDispatcher) {
            if (_subscriptions.value.loaded) loadSubscriptions()
            if (_browse.value.loaded) search(_browse.value.query)
        }
        return result
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MIN_DIRECTORY_QUERY = 2
        const val DIRECTORY_LIMIT = 25
        const val BROWSE_PAGE_SIZE = 50
    }
}
