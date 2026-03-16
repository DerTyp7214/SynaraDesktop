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
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArtistSongsScreenModel(
    private val artistId: PlatformUUID,
    private val rpcServiceManager: RpcServiceManager,
    private val artistService: IArtistService,
    private val songService: ISongService,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("ArtistSongsScreenModel")

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    private val _state = MutableStateFlow<ArtistSongsState>(ArtistSongsState.Loading())
    val state = _state.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadArtist()
            loadSongs()
        }
    }

    private fun loadArtist() {
        screenModelScope.launch {
            try {
                val artist = artistService.byId(artistId)
                val currentState = _state.value
                if (currentState is ArtistSongsState.Success) {
                    _state.value = currentState.copy(artist = artist)
                } else if (currentState is ArtistSongsState.Loading) {
                    _state.value = ArtistSongsState.Loading(artist = artist)
                }
            } catch (_: Exception) {}
        }
    }

    fun loadSongs() {
        if (isFetching || !hasNextPage) return
        isFetching = true

        screenModelScope.launch(modelDispatcher) {
            try {
                val response = songService.byArtist(currentPage, pageSize, artistId)
                val currentSongs = (_state.value as? ArtistSongsState.Success)?.songs ?: emptyList()
                val artist = (_state.value as? ArtistSongsState.Success)?.artist 
                    ?: (_state.value as? ArtistSongsState.Loading)?.artist

                _state.value = ArtistSongsState.Success(
                    artist = artist,
                    songs = currentSongs + response.data,
                    hasNextPage = response.hasNextPage
                )

                hasNextPage = response.hasNextPage
                if (hasNextPage) {
                    currentPage++
                }
            } catch (e: Exception) {
                if (currentPage == 0) {
                    _state.value = ArtistSongsState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is ArtistSongsState.Success) {
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = PlaybackSource.Artist(artistId)),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class ArtistSongsState {
        data class Loading(val artist: Artist? = null) : ArtistSongsState()
        data class Success(
            val artist: Artist?,
            val songs: List<UserSong>,
            val hasNextPage: Boolean
        ) : ArtistSongsState()
        data class Error(val message: String) : ArtistSongsState()
    }
}
