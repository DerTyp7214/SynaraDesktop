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
import dev.dertyp.synara.player.QueueEntry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumState(
    val album: Album? = null,
    val songs: List<UserSong> = emptyList(),
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
                val album = albumService.byId(albumId)
                val songsResponse = songService.byAlbum(0, 1000, albumId)
                mutableState.update { it.copy(album = album, songs = songsResponse.data, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun playAlbum(startIndex: Int = 0) {
        val state = state.value
        val album = state.album ?: return
        playerModel.playQueue(
            PlaybackQueue(
                source = PlaybackSource.Album(album.id, album.name),
                items = state.songs.map { QueueEntry.Explicit(it) }
            ),
            startIndex = startIndex
        )
    }

    fun addToQueue() {
        val state = state.value
        val album = state.album ?: return
        playerModel.addToQueue(
            PlaybackQueue(
                source = PlaybackSource.Album(album.id, album.name),
                items = state.songs.map { QueueEntry.Explicit(it) }
            )
        )
    }

    fun playNext(song: UserSong) {
        playerModel.playNext(song)
    }
}
