package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.SongTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AllSongsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("AllSongsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    private val _state = MutableStateFlow<AllSongsState>(AllSongsState.Loading)
    val state = _state.asStateFlow()

    val pageSize = 150
    private var pendingFilter = SongFilter()
    private val loadingPages = mutableSetOf<Int>()

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadInitialData()
        }

        screenModelScope.launch(modelDispatcher) {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> {
                        val currentState = _state.value
                        if (currentState is AllSongsState.Success) {
                            val updatedSong = update.song
                            val songs = currentState.songs.toMutableList()
                            val index = songs.indexOfFirst { it?.id == updatedSong.id }
                            if (index != -1) {
                                songs[index] = updatedSong
                                _state.value = currentState.copy(songs = songs)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadInitialData() {
        screenModelScope.launch(modelDispatcher) {
            val filter = currentFilter()
            _state.value = AllSongsState.Loading
            try {
                _state.value = fetchFirstPage(filter)
            } catch (e: Exception) {
                _state.value = AllSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun currentFilter(): SongFilter = (_state.value as? AllSongsState.Success)?.filter ?: pendingFilter

    private suspend fun fetchFirstPage(filter: SongFilter): AllSongsState.Success {
        generation++
        loadingPages.clear()
        pendingFilter = filter
        val response = songService.allSongs(
            0, pageSize, true, filter.tags, filter.excludeTags, filter.titleTags, filter.excludeTitleTags
        )
        val total = response.total
        val songs = arrayOfNulls<UserSong>(total).toMutableList()

        for (i in response.data.indices) {
            if (i < songs.size) songs[i] = response.data[i]
        }

        return AllSongsState.Success(
            songs = songs,
            total = total,
            tags = filter.tags,
            excludeTags = filter.excludeTags,
            titleTags = filter.titleTags,
            excludeTitleTags = filter.excludeTitleTags
        )
    }

    private suspend fun reload() {
        if (_state.value is AllSongsState.Loading) return
        val current = _state.value as? AllSongsState.Success
        val requestGeneration = generation + 1
        try {
            val refreshed = fetchFirstPage(current?.filter ?: pendingFilter)
            if (requestGeneration != generation) return
            _state.value = refreshed
            songCache.refreshCached(refreshed.songs.filterNotNull())
        } catch (e: Exception) {
            if (current == null) _state.value = AllSongsState.Error(e.message ?: "Unknown error")
        }
    }

    fun loadPage(page: Int) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        if (loadingPages.contains(page)) return
        
        val offset = page * pageSize
        if (offset >= currentState.total) return

        if (currentState.songs.getOrNull(offset) != null) return

        loadingPages.add(page)
        val requestGeneration = generation
        screenModelScope.launch(modelDispatcher) {
            try {
                val response = songService.allSongs(
                    page,
                    pageSize,
                    true,
                    currentState.tags,
                    currentState.excludeTags,
                    currentState.titleTags,
                    currentState.excludeTitleTags
                )
                if (requestGeneration != generation) return@launch
                val latestState = _state.value as? AllSongsState.Success ?: return@launch
                val updatedSongs = latestState.songs.toMutableList()
                
                for (i in response.data.indices) {
                    val index = offset + i
                    if (index < updatedSongs.size) {
                        updatedSongs[index] = response.data[i]
                    }
                }
                
                _state.value = latestState.copy(songs = updatedSongs)
            } catch (_: Exception) {
            } finally {
                if (requestGeneration == generation) loadingPages.remove(page)
            }
        }
    }

    fun cycleTag(tag: SongTag) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        val (tags, excludeTags) = cycleFilter(tag, currentState.tags, currentState.excludeTags)
        _state.value = currentState.copy(tags = tags, excludeTags = excludeTags)
        applyFilters()
    }

    fun cycleTitleTag(kind: TitleTagKind) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        val (titleTags, excludeTitleTags) = cycleFilter(kind, currentState.titleTags, currentState.excludeTitleTags)
        _state.value = currentState.copy(titleTags = titleTags, excludeTitleTags = excludeTitleTags)
        applyFilters()
    }

    private fun applyFilters() {
        loadingPages.clear()
        loadInitialData()
    }

    override fun refresh() {
        refresher.refresh()
    }

    fun playAll() {
        val currentState = _state.value as? AllSongsState.Success ?: return
        playerModel.playQueue(PlaybackQueue(source = currentState.filter.toPlaybackSource()))
    }

    fun playSong(song: UserSong, index: Int) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        playerModel.playQueue(
            PlaybackQueue(source = currentState.filter.toPlaybackSource()),
            startIndex = index
        )
    }

    sealed class AllSongsState {
        data object Loading : AllSongsState()
        data class Success(
            val songs: List<UserSong?>,
            val total: Int,
            val tags: List<SongTag> = emptyList(),
            val excludeTags: List<SongTag> = emptyList(),
            val titleTags: List<TitleTagKind> = emptyList(),
            val excludeTitleTags: List<TitleTagKind> = emptyList()
        ) : AllSongsState() {
            val filter: SongFilter
                get() = SongFilter(tags, excludeTags, titleTags, excludeTitleTags)

            fun stateOf(tag: SongTag): TagFilterState = filterStateOf(tag, tags, excludeTags)

            fun stateOf(kind: TitleTagKind): TagFilterState = filterStateOf(kind, titleTags, excludeTitleTags)
        }
        data class Error(val message: String) : AllSongsState()
    }
}

data class SongFilter(
    val tags: List<SongTag> = emptyList(),
    val excludeTags: List<SongTag> = emptyList(),
    val titleTags: List<TitleTagKind> = emptyList(),
    val excludeTitleTags: List<TitleTagKind> = emptyList()
) {
    fun toPlaybackSource(): PlaybackSource.AllSongs = PlaybackSource.AllSongs(
        tags = tags,
        excludeTags = excludeTags,
        titleTags = titleTags,
        excludeTitleTags = excludeTitleTags
    )
}

enum class TagFilterState { OFF, INCLUDE, EXCLUDE }

fun <T> filterStateOf(item: T, include: List<T>, exclude: List<T>): TagFilterState = when (item) {
    in include -> TagFilterState.INCLUDE
    in exclude -> TagFilterState.EXCLUDE
    else -> TagFilterState.OFF
}

fun <T> cycleFilter(item: T, include: List<T>, exclude: List<T>): Pair<List<T>, List<T>> =
    when (filterStateOf(item, include, exclude)) {
        TagFilterState.OFF -> (include + item) to exclude
        TagFilterState.INCLUDE -> (include - item) to (exclude + item)
        TagFilterState.EXCLUDE -> include to (exclude - item)
    }
