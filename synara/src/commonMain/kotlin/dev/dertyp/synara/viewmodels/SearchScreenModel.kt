package dev.dertyp.synara.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class SearchScreenModel(
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val artistService: IArtistService,
    private val userPlaylistService: IUserPlaylistService,
    private val globalStateModel: GlobalStateModel,
    dispatchers: SynaraDispatchers
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("SearchScreenModel")

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    val lazyListState = LazyListState()

    private val _songs = MutableStateFlow<List<UserSong>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists = _artists.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _songs.value = emptyList()
            _albums.value = emptyList()
            _artists.value = emptyList()
            _playlists.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = screenModelScope.launch(modelDispatcher) {
            delay(400.milliseconds)
            _isSearching.value = true
            try {
                val songsJob = launch { _songs.value = songService.rankedSearch(0, 10, query, explicit = true).data }
                val albumsJob = launch { _albums.value = albumService.rankedSearch(0, 10, query).data }
                val artistsJob = launch { _artists.value = artistService.rankedSearch(0, 10, query).data }
                val playlistsJob = launch { _playlists.value = userPlaylistService.rankedSearch(globalStateModel.user.value?.id, 0, 10, query).data }

                songsJob.join()
                albumsJob.join()
                artistsJob.join()
                playlistsJob.join()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSearching.value = false
            }
        }
    }
}
