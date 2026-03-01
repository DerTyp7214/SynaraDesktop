package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistState(
    val artist: Artist? = null,
    val topSongs: List<UserSong> = emptyList(),
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false
)

class ArtistScreenModel(
    private val artistId: PlatformUUID,
    private val artistService: IArtistService,
    private val songService: ISongService,
    private val albumService: IAlbumService,
    val playerModel: PlayerModel
) : StateScreenModel<ArtistState>(ArtistState()) {

    init {
        loadArtist()
    }

    private fun loadArtist() {
        screenModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                val artist = artistService.byId(artistId)
                val songsResponse = songService.byArtist(0, 5, artistId)
                val albumsResponse = albumService.byArtist(0, 20, artistId)
                
                mutableState.update { 
                    it.copy(
                        artist = artist, 
                        topSongs = songsResponse.data, 
                        albums = albumsResponse.data,
                        isLoading = false
                    ) 
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun playArtist(startIndex: Int = 0) {
        playerModel.playQueue(
            PlaybackQueue(
                source = PlaybackSource.Artist(artistId)
            ),
            startIndex = startIndex
        )
    }

    fun playSong(song: UserSong) {
        val index = state.value.topSongs.indexOf(song)
        if (index != -1) {
             playerModel.playQueue(
                PlaybackQueue(
                    source = PlaybackSource.Artist(artistId)
                ),
                startIndex = index
            )
        } else {
            playerModel.playNext(song)
            playerModel.skipNext()
        }
    }

    fun playNext(song: UserSong) {
        playerModel.playNext(song)
    }
}
