package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumState(
    val album: Album? = null,
    val songs: List<UserSong> = emptyList(),
    val versions: List<Album> = emptyList(),
    val totalDuration: Long = 0,
    val isLoading: Boolean = false
)

class AlbumScreenModel(
    private val albumId: PlatformUUID,
    private val albumService: IAlbumService,
    private val songService: ISongService,
    val playerModel: PlayerModel
) : StateScreenModel<AlbumState>(AlbumState()) {

    init {
        loadAlbum()
    }

    private fun loadAlbum() {
        screenModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                coroutineScope {
                    val albumDeferred = async { albumService.byId(albumId) }
                    val songsDeferred = async { songService.byAlbum(0, Int.MAX_VALUE, albumId) }
                    val versionsDeferred = async { albumService.versions(albumId) }

                    val album = albumDeferred.await()
                    val songsResponse = songsDeferred.await()
                    val versions = versionsDeferred.await()

                    mutableState.update {
                        it.copy(
                            album = album,
                            songs = songsResponse.data,
                            versions = versions,
                            totalDuration = songsResponse.data.sumOf { s -> s.duration },
                            isLoading = false
                        )
                    }
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun playAlbum(startIndex: Int = 0) {
        val state = state.value
        val album = state.album ?: return
        playerModel.playQueue(
            PlaybackQueue(
                source = PlaybackSource.Album(album.id)
            ),
            startIndex = startIndex
        )
    }

    fun addToQueue() {
        val state = state.value
        val album = state.album ?: return
        playerModel.addToQueue(
            PlaybackQueue(
                source = PlaybackSource.Album(album.id)
            )
        )
    }

    fun playNext(song: UserSong) {
        playerModel.playNext(song)
    }
}
