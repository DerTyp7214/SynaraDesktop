package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LikedSongsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel
) : ScreenModel {

    private val _state = MutableStateFlow<LikedSongsState>(LikedSongsState.Loading)
    val state = _state.asStateFlow()

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
                                if (updatedSong.isFavourite == false) {
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
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        currentPage = 0
        hasNextPage = true
        loadLikedSongs()
    }

    fun loadLikedSongs() {
        if (isFetching) return
        isFetching = true
        
        screenModelScope.launch {
            if (currentPage == 0) {
                _state.value = LikedSongsState.Loading
            }
            
            try {
                val songsResponse = songService.likedSongs(currentPage, pageSize, true)
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
                if (currentPage == 0) {
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

    fun playAll() {
        playerModel.playQueue(PlaybackQueue(source = PlaybackSource.LikedSongs))
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is LikedSongsState.Success) {
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = PlaybackSource.LikedSongs),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class LikedSongsState {
        data object Loading : LikedSongsState()
        data class Success(val songs: List<UserSong>, val hasNextPage: Boolean) : LikedSongsState()
        data class Error(val message: String) : LikedSongsState()
    }
}
