package dev.dertyp.synara.viewmodels

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseSearchViewModel<T>(val query: String) : ScreenModel {
    protected val _items = MutableStateFlow<List<T>>(emptyList())
    val items = _items.asStateFlow()

    protected val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    protected var currentPage = 0
    protected val pageSize = 20
    private var _hasNextPage = MutableStateFlow(true)
    val hasNextPage = _hasNextPage.asStateFlow()

    fun loadMore() {
        if (_isLoading.value || !_hasNextPage.value) return
        fetchPage()
    }

    protected abstract fun fetchPage()

    protected fun handleResult(newItems: List<T>) {
        _items.value += newItems
        if (newItems.size < pageSize) {
            _hasNextPage.value = false
        }
        currentPage++
        _isLoading.value = false
    }
}

class SearchSongsViewModel(
    private val songService: ISongService,
    query: String
) : BaseSearchViewModel<UserSong>(query) {
    init { loadMore() }
    override fun fetchPage() {
        screenModelScope.launch {
            _isLoading.value = true
            val result = songService.rankedSearch(currentPage * pageSize, pageSize, query, explicit = true)
            handleResult(result.data)
        }
    }
}

class SearchArtistsViewModel(
    private val artistService: IArtistService,
    query: String
) : BaseSearchViewModel<Artist>(query) {
    init { loadMore() }
    override fun fetchPage() {
        screenModelScope.launch {
            _isLoading.value = true
            val result = artistService.rankedSearch(currentPage * pageSize, pageSize, query)
            handleResult(result.data)
        }
    }
}

class SearchAlbumsViewModel(
    private val albumService: IAlbumService,
    query: String
) : BaseSearchViewModel<Album>(query) {
    init { loadMore() }
    override fun fetchPage() {
        screenModelScope.launch {
            _isLoading.value = true
            val result = albumService.rankedSearch(currentPage * pageSize, pageSize, query)
            handleResult(result.data)
        }
    }
}

class SearchPlaylistsViewModel(
    private val playlistService: IUserPlaylistService,
    query: String
) : BaseSearchViewModel<UserPlaylist>(query) {
    init { loadMore() }
    override fun fetchPage() {
        screenModelScope.launch {
            _isLoading.value = true
            val result = playlistService.rankedSearch(null, currentPage * pageSize, pageSize, query)
            handleResult(result.data)
        }
    }
}
