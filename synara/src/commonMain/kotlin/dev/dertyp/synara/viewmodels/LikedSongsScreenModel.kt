package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.components.effectiveLikeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LikedFilter {
    All, Super
}

class LikedSongsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    private val downloadManager: IDownloadManager
) : ScreenModel, Refreshable {

    private val refresher = RefreshCoalescer(screenModelScope) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    private val _state = MutableStateFlow<LikedSongsState>(LikedSongsState.Loading)
    val state = _state.asStateFlow()

    private val _filter = MutableStateFlow(LikedFilter.All)
    val filter: StateFlow<LikedFilter> = _filter.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch {
            rpcServiceManager.awaitAuthentication()
            loadLikedSongs()
        }

        screenModelScope.launch {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> {
                        val currentState = _state.value
                        if (currentState is LikedSongsState.Success) {
                            val updatedSong = update.song
                            val songs = currentState.songs.toMutableList()
                            val index = songs.indexOfFirst { it.id == updatedSong.id }
                            if (index != -1) {
                                val shouldRemove = updatedSong.isFavourite == false ||
                                    (_filter.value == LikedFilter.Super && updatedSong.effectiveLikeLevel != LikeLevel.SUPER)
                                if (shouldRemove) {
                                    songs.removeAt(index)
                                } else {
                                    songs[index] = updatedSong
                                }
                                _state.value = currentState.copy(songs = songs)
                            }
                        }
                    }
                }
            }
        }

        screenModelScope.launch {
            songCache.playlistUpdates.collect { update ->
                if (update is PlaylistUpdate.LikedSongsReloadRequired) {
                    screenModelScope.launch { reload() }
                }
            }
        }
    }

    override fun refresh() {
        refresher.refresh()
    }

    fun setFilter(filter: LikedFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        currentPage = 0
        hasNextPage = true
        ++generation
        loadLikedSongs()
    }

    private suspend fun fetchPage(page: Int): PaginatedResponse<UserSong> {
        return when (_filter.value) {
            LikedFilter.All -> songService.likedSongs(page, pageSize, true)
            LikedFilter.Super -> songService.superLikedSongs(page, pageSize, true)
        }
    }

    private suspend fun reload() {
        val requestGeneration = ++generation
        try {
            val songsResponse = fetchPage(0)
            if (requestGeneration != generation) return
            _state.value = LikedSongsState.Success(
                songs = songsResponse.data,
                hasNextPage = songsResponse.hasNextPage
            )
            hasNextPage = songsResponse.hasNextPage
            currentPage = if (hasNextPage) 1 else 0
            songCache.refreshCached(songsResponse.data)
        } catch (e: Exception) {
            if (_state.value !is LikedSongsState.Success) {
                _state.value = LikedSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadLikedSongs() {
        if (isFetching) return
        isFetching = true
        val requestGeneration = generation

        screenModelScope.launch {
            if (currentPage == 0) {
                _state.value = LikedSongsState.Loading
            }

            try {
                val songsResponse = fetchPage(currentPage)
                if (requestGeneration != generation) return@launch
                val currentSongs = if (currentPage == 0) emptyList() else (_state.value as? LikedSongsState.Success)?.songs ?: emptyList()

                _state.value = LikedSongsState.Success(
                    songs = currentSongs + songsResponse.data,
                    hasNextPage = songsResponse.hasNextPage
                )

                hasNextPage = songsResponse.hasNextPage
                if (hasNextPage) {
                    currentPage++
                }
            } catch (e: Exception) {
                if (currentPage == 0 && requestGeneration == generation) {
                    _state.value = LikedSongsState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun loadNextPage() {
        if (hasNextPage && !isFetching) {
            loadLikedSongs()
        }
    }

    private val playbackSource: PlaybackSource
        get() = when (_filter.value) {
            LikedFilter.All -> PlaybackSource.LikedSongs
            LikedFilter.Super -> PlaybackSource.SuperLikedSongs
        }

    fun playAll() {
        playerModel.playQueue(PlaybackQueue(source = playbackSource))
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is LikedSongsState.Success) {
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = playbackSource),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    fun downloadFavorites() {
        downloadManager.downloadFavorites()
    }

    sealed class LikedSongsState {
        data object Loading : LikedSongsState()
        data class Success(val songs: List<UserSong>, val hasNextPage: Boolean) : LikedSongsState()
        data class Error(val message: String) : LikedSongsState()
    }
}
