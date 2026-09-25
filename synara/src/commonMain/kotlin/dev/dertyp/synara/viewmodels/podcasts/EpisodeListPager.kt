package dev.dertyp.synara.viewmodels.podcasts

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.synara.podcast.PodcastProgressStore
import dev.dertyp.synara.podcast.withProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

data class EpisodeListState(
    val episodes: List<PodcastEpisode> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false
) {
    val hasMore: Boolean get() = loaded && episodes.size < total
}

class EpisodeListPager(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val progressStore: PodcastProgressStore,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val fetch: suspend (page: Int, pageSize: Int) -> PaginatedResponse<PodcastEpisode>
) {
    private val _state = MutableStateFlow(EpisodeListState())
    val state: StateFlow<EpisodeListState> = _state.asStateFlow()

    private var generation = 0
    private var nextPage = 0

    fun ensureLoaded() {
        val current = _state.value
        if (current.loaded || current.isLoading) return
        scope.launch(context) { reload() }
    }

    fun load() {
        scope.launch(context) { reload() }
    }

    suspend fun reload() {
        val requestGeneration = ++generation
        val hadContent = _state.value.loaded
        _state.update { it.copy(isLoading = !hadContent, isLoadingMore = false, failed = false) }
        try {
            val response = fetch(0, pageSize)
            if (requestGeneration != generation) return
            nextPage = 1
            _state.value = EpisodeListState(
                episodes = response.data.withProgress(progressStore.latest.value),
                total = response.total,
                loaded = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (requestGeneration != generation) return
            _state.update { it.copy(isLoading = false, failed = !hadContent) }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        val requestGeneration = generation
        val page = nextPage
        _state.update { it.copy(isLoadingMore = true) }
        scope.launch(context) {
            try {
                val response = fetch(page, pageSize)
                if (requestGeneration != generation) return@launch
                nextPage = page + 1
                val fresh = response.data.withProgress(progressStore.latest.value)
                _state.update { state ->
                    val known = state.episodes.mapTo(HashSet()) { it.id }
                    state.copy(
                        episodes = state.episodes + fresh.filter { it.id !in known },
                        total = if (response.data.isEmpty()) state.episodes.size else response.total,
                        isLoadingMore = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun applyProgress(progress: PodcastEpisodeProgress) {
        _state.update { state ->
            if (state.episodes.none { it.id == progress.episodeId }) state
            else state.copy(episodes = state.episodes.map { it.withProgress(progress) })
        }
    }

    fun replace(episode: PodcastEpisode) {
        _state.update { state ->
            if (state.episodes.none { it.id == episode.id }) state
            else state.copy(episodes = state.episodes.map { if (it.id == episode.id) episode.withProgress(progressStore.latest.value) else it })
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
