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
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.toEpochMilliseconds
import kotlinx.coroutines.CancellationException
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
    private val downloadManager: IDownloadManager,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("LikedSongsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    private val _state = MutableStateFlow<LikedSongsState>(LikedSongsState.Loading)
    val state = _state.asStateFlow()

    private val _filter = MutableStateFlow(LikedFilter.All)
    val filter: StateFlow<LikedFilter> = _filter.asStateFlow()

    private var currentPage = 0
    private var pageShift = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadLikedSongs()
        }

        screenModelScope.launch(modelDispatcher) {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> applySongUpdate(update.song)
                }
            }
        }
    }

    private fun applySongUpdate(updatedSong: UserSong) {
        val currentState = _state.value as? LikedSongsState.Success ?: return
        val filter = _filter.value
        val songs = currentState.songs.toMutableList()
        val index = songs.indexOfFirst { it.id == updatedSong.id }
        if (index != -1) {
            val shouldRemove = updatedSong.isFavourite == false ||
                (filter == LikedFilter.Super && updatedSong.effectiveLikeLevel != LikeLevel.SUPER)
            if (shouldRemove) {
                songs.removeAt(index)
                shiftPaging(-1)
            } else {
                songs[index] = updatedSong
            }
        } else {
            val key = filter.sortKey(updatedSong) ?: return
            val listed = when (filter) {
                LikedFilter.All -> updatedSong.isFavourite == true
                LikedFilter.Super -> updatedSong.effectiveLikeLevel == LikeLevel.SUPER
            }
            if (!listed) return
            val insertAt = songs.indexOfFirst { (filter.sortKey(it) ?: Long.MIN_VALUE) < key }
            if (insertAt == -1 && currentState.hasNextPage) return
            songs.add(if (insertAt == -1) songs.size else insertAt, updatedSong)
            shiftPaging(1)
        }
        _state.value = currentState.copy(songs = songs)
    }

    private fun LikedFilter.sortKey(song: UserSong): Long? = when (this) {
        LikedFilter.All -> song.userSongUpdatedAt
        LikedFilter.Super -> song.superLikedAt
    }?.toEpochMilliseconds()

    private fun shiftPaging(by: Int) {
        pageShift += by
        ++generation
        isFetching = false
    }

    override fun refresh() {
        refresher.refresh()
    }

    fun setFilter(filter: LikedFilter) {
        screenModelScope.launch(modelDispatcher) {
            if (_filter.value == filter) return@launch
            _filter.value = filter
            currentPage = 0
            pageShift = 0
            hasNextPage = true
            ++generation
            isFetching = false
            loadLikedSongs()
        }
    }

    private suspend fun fetchPage(page: Int): PaginatedResponse<UserSong> {
        return when (_filter.value) {
            LikedFilter.All -> songService.likedSongs(page, pageSize, true)
            LikedFilter.Super -> songService.superLikedSongs(page, pageSize, true)
        }
    }

    private suspend fun reload() {
        val requestGeneration = ++generation
        isFetching = false
        try {
            val songsResponse = fetchPage(0)
            if (requestGeneration != generation) return
            ++generation
            isFetching = false
            _state.value = LikedSongsState.Success(
                songs = songsResponse.data,
                hasNextPage = songsResponse.hasNextPage
            )
            hasNextPage = songsResponse.hasNextPage
            currentPage = if (hasNextPage) 1 else 0
            pageShift = 0
            songCache.refreshCached(songsResponse.data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestGeneration == generation && _state.value !is LikedSongsState.Success) {
                _state.value = LikedSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadLikedSongs() {
        screenModelScope.launch(modelDispatcher) {
            if (isFetching) return@launch
            isFetching = true
            val requestGeneration = generation
            val firstPage = currentPage == 0
            val offset = if (firstPage) 0 else (currentPage * pageSize + pageShift).coerceAtLeast(0)
            val page = offset / pageSize
            val requestFilter = _filter.value
            if (firstPage) {
                _state.value = LikedSongsState.Loading
            }

            try {
                val songsResponse = fetchPage(page)
                if (requestGeneration != generation) {
                    if (!firstPage && requestFilter == _filter.value) loadNextPage()
                    return@launch
                }
                val currentSongs = if (firstPage) emptyList() else (_state.value as? LikedSongsState.Success)?.songs ?: emptyList()
                val known = currentSongs.mapTo(HashSet()) { it.id }

                _state.value = LikedSongsState.Success(
                    songs = currentSongs + songsResponse.data.drop(offset % pageSize).filter { it.id !in known },
                    hasNextPage = songsResponse.hasNextPage
                )

                hasNextPage = songsResponse.hasNextPage
                pageShift = 0
                if (hasNextPage) {
                    currentPage = page + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstPage && requestGeneration == generation) {
                    _state.value = LikedSongsState.Error(e.message ?: "Unknown error")
                }
            } finally {
                if (requestGeneration == generation) isFetching = false
            }
        }
    }

    fun loadNextPage() {
        screenModelScope.launch(modelDispatcher) {
            if (hasNextPage && !isFetching) {
                loadLikedSongs()
            }
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
