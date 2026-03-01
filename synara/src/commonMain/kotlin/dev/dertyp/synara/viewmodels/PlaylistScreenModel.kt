package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistScreenModel(
    private val playlistId: PlatformUUID,
    private val isUserPlaylist: Boolean,
    private val playlistService: IPlaylistService,
    private val userPlaylistService: IUserPlaylistService,
    private val songService: ISongService,
    val playerModel: PlayerModel
) : ScreenModel {

    private val _state = MutableStateFlow<PlaylistState>(PlaylistState.Loading)
    val state = _state.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        if (isFetching) return
        isFetching = true

        screenModelScope.launch {
            if (currentPage == 0) {
                _state.value = PlaylistState.Loading
            }

            try {
                val currentSongs = (_state.value as? PlaylistState.Success)?.songs ?: emptyList()
                val playlistName = (_state.value as? PlaylistState.Success)?.name

                if (isUserPlaylist) {
                    val playlist = if (currentPage == 0) userPlaylistService.byId(playlistId) else null
                    val name = playlist?.name ?: playlistName ?: "Playlist"
                    
                    val songsResponse = songService.byUserPlaylist(currentPage, pageSize, playlistId)
                    
                    _state.value = PlaylistState.Success(
                        name = name,
                        songs = currentSongs + songsResponse.data,
                        isUserPlaylist = isUserPlaylist,
                        hasNextPage = songsResponse.hasNextPage
                    )
                    hasNextPage = songsResponse.hasNextPage
                } else {
                    val playlist = if (currentPage == 0) playlistService.byId(playlistId) else null
                    val name = playlist?.name ?: playlistName ?: "Playlist"

                    val songsResponse = songService.byPlaylist(currentPage, pageSize, playlistId)
                    
                    _state.value = PlaylistState.Success(
                        name = name,
                        songs = currentSongs + songsResponse.data,
                        isUserPlaylist = isUserPlaylist,
                        hasNextPage = songsResponse.hasNextPage
                    )
                    hasNextPage = songsResponse.hasNextPage
                }

                if (hasNextPage) {
                    currentPage++
                }
            } catch (e: Exception) {
                if (currentPage == 0) {
                    _state.value = PlaylistState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun loadNextPage() {
        if (hasNextPage && !isFetching) {
            loadPlaylist()
        }
    }

    fun playPlaylist() {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId, currentState.name, isUserPlaylist)
            playerModel.playQueue(PlaybackQueue(source = source))
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId, currentState.name, isUserPlaylist)
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = source),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class PlaylistState {
        data object Loading : PlaylistState()
        data class Success(
            val name: String, 
            val songs: List<UserSong>, 
            val isUserPlaylist: Boolean,
            val hasNextPage: Boolean
        ) : PlaylistState()
        data class Error(val message: String) : PlaylistState()
    }
}
