package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.SongTag
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AllSongsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("AllSongsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    private val _state = MutableStateFlow<AllSongsState>(AllSongsState.Loading)
    val state = _state.asStateFlow()

    val pageSize = 150
    private val loadingPages = mutableSetOf<Int>()

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadInitialData()
        }

        screenModelScope.launch(modelDispatcher) {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> {
                        val currentState = _state.value
                        if (currentState is AllSongsState.Success) {
                            val updatedSong = update.song
                            val songs = currentState.songs.toMutableList()
                            val index = songs.indexOfFirst { it?.id == updatedSong.id }
                            if (index != -1) {
                                songs[index] = updatedSong
                                _state.value = currentState.copy(songs = songs)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadInitialData() {
        screenModelScope.launch(modelDispatcher) {
            val tags = (_state.value as? AllSongsState.Success)?.tags ?: emptyList()
            val invertTags = (_state.value as? AllSongsState.Success)?.invertTags ?: false
            
            _state.value = AllSongsState.Loading
            try {
                _state.value = fetchFirstPage(tags, invertTags)
            } catch (e: Exception) {
                _state.value = AllSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun fetchFirstPage(tags: List<SongTag>, invertTags: Boolean): AllSongsState.Success {
        generation++
        loadingPages.clear()
        val response = songService.allSongs(0, pageSize, true, tags, invertTags)
        val total = response.total
        val songs = arrayOfNulls<UserSong>(total).toMutableList()

        for (i in response.data.indices) {
            if (i < songs.size) songs[i] = response.data[i]
        }

        return AllSongsState.Success(
            songs = songs,
            total = total,
            tags = tags,
            invertTags = invertTags
        )
    }

    private suspend fun reload() {
        if (_state.value is AllSongsState.Loading) return
        val current = _state.value as? AllSongsState.Success
        val requestGeneration = generation + 1
        try {
            val refreshed = fetchFirstPage(current?.tags ?: emptyList(), current?.invertTags ?: false)
            if (requestGeneration != generation) return
            _state.value = refreshed
            songCache.refreshCached(refreshed.songs.filterNotNull())
        } catch (e: Exception) {
            if (current == null) _state.value = AllSongsState.Error(e.message ?: "Unknown error")
        }
    }

    fun loadPage(page: Int) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        if (loadingPages.contains(page)) return
        
        val offset = page * pageSize
        if (offset >= currentState.total) return

        if (currentState.songs.getOrNull(offset) != null) return

        loadingPages.add(page)
        val requestGeneration = generation
        screenModelScope.launch(modelDispatcher) {
            try {
                val response = songService.allSongs(page, pageSize, true, currentState.tags, currentState.invertTags)
                if (requestGeneration != generation) return@launch
                val latestState = _state.value as? AllSongsState.Success ?: return@launch
                val updatedSongs = latestState.songs.toMutableList()
                
                for (i in response.data.indices) {
                    val index = offset + i
                    if (index < updatedSongs.size) {
                        updatedSongs[index] = response.data[i]
                    }
                }
                
                _state.value = latestState.copy(songs = updatedSongs)
            } catch (_: Exception) {
            } finally {
                if (requestGeneration == generation) loadingPages.remove(page)
            }
        }
    }

    fun toggleTag(tag: SongTag) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        val currentTags = currentState.tags.toMutableList()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _state.value = currentState.copy(tags = currentTags)
        applyFilters()
    }

    fun setInvertTags(invert: Boolean) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        if (currentState.invertTags == invert) return
        
        _state.value = currentState.copy(invertTags = invert)
        applyFilters()
    }

    private fun applyFilters() {
        loadingPages.clear()
        loadInitialData()
    }

    override fun refresh() {
        refresher.refresh()
    }

    fun playAll() {
        val currentState = _state.value as? AllSongsState.Success ?: return
        playerModel.playQueue(PlaybackQueue(source = PlaybackSource.AllSongs(tags = currentState.tags, invertTags = currentState.invertTags)))
    }

    fun playSong(song: UserSong, index: Int) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        playerModel.playQueue(
            PlaybackQueue(source = PlaybackSource.AllSongs(tags = currentState.tags, invertTags = currentState.invertTags)),
            startIndex = index
        )
    }

    sealed class AllSongsState {
        data object Loading : AllSongsState()
        data class Success(
            val songs: List<UserSong?>,
            val total: Int,
            val tags: List<SongTag> = emptyList(),
            val invertTags: Boolean = false
        ) : AllSongsState()
        data class Error(val message: String) : AllSongsState()
    }
}
