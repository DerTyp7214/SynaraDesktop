package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ArtistLikedSongsScreenModel(
    private val artistId: PlatformUUID,
    private val rpcServiceManager: RpcServiceManager,
    private val artistService: IArtistService,
    private val songService: ISongService,
    val playerModel: PlayerModel
) : ScreenModel {

    private val _state = MutableStateFlow<ArtistLikedSongsState>(ArtistLikedSongsState.Loading())
    val state = _state.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch {
            rpcServiceManager.awaitAuthentication()
            loadArtist()
            loadSongs()
        }
    }

    private fun loadArtist() {
        screenModelScope.launch {
            try {
                val artist = artistService.byId(artistId)
                _state.update { currentState ->
                    when (currentState) {
                        is ArtistLikedSongsState.Success -> currentState.copy(artist = artist)
                        is ArtistLikedSongsState.Loading -> currentState.copy(artist = artist)
                        else -> currentState
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadSongs() {
        if (isFetching || !hasNextPage) return
        isFetching = true

        screenModelScope.launch {
            try {
                val response = songService.likedByArtist(currentPage, pageSize, artistId, true)
                
                _state.update { currentState ->
                    val currentSongs = (currentState as? ArtistLikedSongsState.Success)?.songs ?: emptyList()
                    val artist = (currentState as? ArtistLikedSongsState.Success)?.artist 
                        ?: (currentState as? ArtistLikedSongsState.Loading)?.artist

                    ArtistLikedSongsState.Success(
                        artist = artist,
                        songs = currentSongs + response.data,
                        hasNextPage = response.hasNextPage
                    )
                }

                hasNextPage = response.hasNextPage
                if (hasNextPage) {
                    currentPage++
                }
            } catch (e: Exception) {
                if (currentPage == 0) {
                    _state.value = ArtistLikedSongsState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is ArtistLikedSongsState.Success) {
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = PlaybackSource.Artist(artistId)),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class ArtistLikedSongsState {
        data class Loading(val artist: Artist? = null) : ArtistLikedSongsState()
        data class Success(
            val artist: Artist?,
            val songs: List<UserSong>,
            val hasNextPage: Boolean
        ) : ArtistLikedSongsState()
        data class Error(val message: String) : ArtistLikedSongsState()
    }
}
