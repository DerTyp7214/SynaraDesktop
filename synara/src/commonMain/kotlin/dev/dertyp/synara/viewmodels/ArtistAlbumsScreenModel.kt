package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ArtistAlbumsScreenModel(
    private val artistId: PlatformUUID,
    private val artistService: IArtistService,
    private val albumService: IAlbumService
) : ScreenModel {

    private val _state = MutableStateFlow<ArtistAlbumsState>(ArtistAlbumsState.Loading())
    val state = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        screenModelScope.launch {
            try {
                coroutineScope {
                    val artistDeferred = async { artistService.byId(artistId) }
                    val albumsDeferred = async { albumService.byArtist(0, Int.MAX_VALUE, artistId) }

                    val artist = artistDeferred.await()
                    val albumsResponse = albumsDeferred.await()

                    _state.value = ArtistAlbumsState.Success(artist, albumsResponse.data)
                }
            } catch (e: Exception) {
                _state.value = ArtistAlbumsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class ArtistAlbumsState {
        data class Loading(val artist: Artist? = null) : ArtistAlbumsState()
        data class Success(val artist: Artist?, val albums: List<Album>) : ArtistAlbumsState()
        data class Error(val message: String) : ArtistAlbumsState()
    }
}
