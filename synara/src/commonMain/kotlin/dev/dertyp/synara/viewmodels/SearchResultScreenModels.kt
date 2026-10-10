package dev.dertyp.synara.viewmodels

import dev.dertyp.logging.Logger
import dev.dertyp.logging.LogTag
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
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseSearchViewModel<T>(
    val query: String,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {
    private val logTag = LogTag("search")

    private val modelDispatcher = dispatchers.createNamed("SearchResultScreenModel")

    protected val _items = MutableStateFlow<List<T>>(emptyList())
    val items = _items.asStateFlow()

    protected val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    protected var currentPage = 0
    protected val pageSize = 20
    private var _hasNextPage = MutableStateFlow(true)
    val hasNextPage = _hasNextPage.asStateFlow()

    private var generation = 0
    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    fun loadMore() {
        screenModelScope.launch(modelDispatcher) {
            if (_isLoading.value || !_hasNextPage.value) return@launch
            val requestGeneration = generation
            val page = currentPage
            _isLoading.value = true
            var dropped = false
            try {
                val newItems = fetch(page, pageSize)
                if (requestGeneration == generation) {
                    handleResult(page, newItems)
                } else {
                    dropped = page > 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(logTag, "Failed to load search results", e)
            } finally {
                _isLoading.value = false
                if (dropped) loadMore()
            }
        }
    }

    override fun refresh() {
        refresher.refresh()
    }

    protected abstract suspend fun fetch(page: Int, limit: Int): List<T>

    protected open suspend fun onRefreshed(items: List<T>) {}

    private suspend fun reload() {
        val requestGeneration = ++generation
        val newItems = fetch(0, pageSize)
        if (requestGeneration != generation) return
        ++generation
        _items.value = newItems
        _hasNextPage.value = newItems.size >= pageSize
        currentPage = 1
        onRefreshed(newItems)
    }

    private fun handleResult(page: Int, newItems: List<T>) {
        _items.value += newItems
        if (newItems.size < pageSize) {
            _hasNextPage.value = false
        }
        currentPage = page + 1
    }
}

class SearchSongsViewModel(
    private val songService: ISongService,
    private val songCache: SongCache,
    logger: Logger,
    dispatchers: SynaraDispatchers,
    query: String
) : BaseSearchViewModel<UserSong>(query, logger, dispatchers) {
    init { loadMore() }
    override suspend fun fetch(page: Int, limit: Int): List<UserSong> =
        songService.rankedSearch(page, limit, query, explicit = true).data

    override suspend fun onRefreshed(items: List<UserSong>) {
        songCache.refreshCached(items)
    }
}

class SearchArtistsViewModel(
    private val artistService: IArtistService,
    logger: Logger,
    dispatchers: SynaraDispatchers,
    query: String
) : BaseSearchViewModel<Artist>(query, logger, dispatchers) {
    init { loadMore() }
    override suspend fun fetch(page: Int, limit: Int): List<Artist> =
        artistService.rankedSearch(page, limit, query).data
}

class SearchAlbumsViewModel(
    private val albumService: IAlbumService,
    logger: Logger,
    dispatchers: SynaraDispatchers,
    query: String
) : BaseSearchViewModel<Album>(query, logger, dispatchers) {
    init { loadMore() }
    override suspend fun fetch(page: Int, limit: Int): List<Album> =
        albumService.rankedSearch(page, limit, query).data
}

class SearchPlaylistsViewModel(
    private val playlistService: IUserPlaylistService,
    logger: Logger,
    dispatchers: SynaraDispatchers,
    query: String
) : BaseSearchViewModel<UserPlaylist>(query, logger, dispatchers) {
    init { loadMore() }
    override suspend fun fetch(page: Int, limit: Int): List<UserPlaylist> =
        playlistService.rankedSearch(null, page, limit, query).data
}
