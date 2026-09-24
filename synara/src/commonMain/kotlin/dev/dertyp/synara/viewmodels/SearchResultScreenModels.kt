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
import dev.dertyp.synara.player.SongCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseSearchViewModel<T>(val query: String) : ScreenModel, Refreshable {
    protected val _items = MutableStateFlow<List<T>>(emptyList())
    val items = _items.asStateFlow()

    protected val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    protected var currentPage = 0
    protected val pageSize = 20
    private var _hasNextPage = MutableStateFlow(true)
    val hasNextPage = _hasNextPage.asStateFlow()

    private var generation = 0
    private val refresher = RefreshCoalescer(screenModelScope) { reload() }
    override val isRefreshing = refresher.isRefreshing

    fun loadMore() {
        if (_isLoading.value || !_hasNextPage.value) return
        fetchPage()
    }

    override fun refresh() {
        refresher.refresh()
    }

    protected abstract suspend fun fetch(offset: Int, limit: Int): List<T>

    protected open suspend fun onRefreshed(items: List<T>) {}

    private fun fetchPage() {
        val requestGeneration = generation
        _isLoading.value = true
        screenModelScope.launch {
            try {
                val newItems = fetch(currentPage * pageSize, pageSize)
                if (requestGeneration == generation) handleResult(newItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (requestGeneration == generation) _isLoading.value = false
            }
        }
    }

    private suspend fun reload() {
        val requestGeneration = ++generation
        try {
            val newItems = fetch(0, pageSize)
            if (requestGeneration != generation) return
            _items.value = newItems
            _hasNextPage.value = newItems.size >= pageSize
            currentPage = 1
            onRefreshed(newItems)
        } finally {
            if (requestGeneration == generation) _isLoading.value = false
        }
    }

    private fun handleResult(newItems: List<T>) {
        _items.value += newItems
        if (newItems.size < pageSize) {
            _hasNextPage.value = false
        }
        currentPage++
    }
}

class SearchSongsViewModel(
    private val songService: ISongService,
    private val songCache: SongCache,
    query: String
) : BaseSearchViewModel<UserSong>(query) {
    init { loadMore() }
    override suspend fun fetch(offset: Int, limit: Int): List<UserSong> =
        songService.rankedSearch(offset, limit, query, explicit = true).data

    override suspend fun onRefreshed(items: List<UserSong>) {
        songCache.refreshCached(items)
    }
}

class SearchArtistsViewModel(
    private val artistService: IArtistService,
    query: String
) : BaseSearchViewModel<Artist>(query) {
    init { loadMore() }
    override suspend fun fetch(offset: Int, limit: Int): List<Artist> =
        artistService.rankedSearch(offset, limit, query).data
}

class SearchAlbumsViewModel(
    private val albumService: IAlbumService,
    query: String
) : BaseSearchViewModel<Album>(query) {
    init { loadMore() }
    override suspend fun fetch(offset: Int, limit: Int): List<Album> =
        albumService.rankedSearch(offset, limit, query).data
}

class SearchPlaylistsViewModel(
    private val playlistService: IUserPlaylistService,
    query: String
) : BaseSearchViewModel<UserPlaylist>(query) {
    init { loadMore() }
    override suspend fun fetch(offset: Int, limit: Int): List<UserPlaylist> =
        playlistService.rankedSearch(null, offset, limit, query).data
}
