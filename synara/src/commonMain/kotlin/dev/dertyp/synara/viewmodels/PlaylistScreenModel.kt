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
import dev.dertyp.synara.player.QueueEntry
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

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        screenModelScope.launch {
            _state.value = PlaylistState.Loading
            try {
                if (isUserPlaylist) {
                    val playlist = userPlaylistService.byId(playlistId)
                    val songsResponse = songService.byUserPlaylist(0, 500, playlistId)
                    if (playlist != null) {
                        _state.value = PlaylistState.Success(playlist.name, songsResponse.data, isUserPlaylist)
                    } else {
                        _state.value = PlaylistState.Error("Playlist not found")
                    }
                } else {
                    val playlist = playlistService.byId(playlistId)
                    val songsResponse = songService.byPlaylist(0, 500, playlistId)
                    if (playlist != null) {
                        _state.value = PlaylistState.Success(playlist.name, songsResponse.data, isUserPlaylist)
                    } else {
                        _state.value = PlaylistState.Error("Playlist not found")
                    }
                }
            } catch (e: Exception) {
                _state.value = PlaylistState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun playPlaylist() {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId, currentState.name, isUserPlaylist)
            val queue = PlaybackQueue(
                source = source,
                items = currentState.songs.map { QueueEntry.FromSource(it.id) }
            )
            playerModel.playQueue(queue)
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId, currentState.name, isUserPlaylist)
            val index = currentState.songs.indexOf(song)
            val queue = PlaybackQueue(
                source = source,
                items = currentState.songs.map { QueueEntry.FromSource(it.id) }
            )
            playerModel.playQueue(queue, if (index != -1) index else 0)
        }
    }

    sealed class PlaylistState {
        object Loading : PlaylistState()
        data class Success(val name: String, val songs: List<UserSong>, val isUserPlaylist: Boolean) : PlaylistState()
        data class Error(val message: String) : PlaylistState()
    }
}
