package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.SongTag
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AllSongsScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel
) : ScreenModel {

    private val _state = MutableStateFlow<AllSongsState>(AllSongsState.Loading)
    val state = _state.asStateFlow()

    val pageSize = 150
    private val loadingPages = mutableSetOf<Int>()

    init {
        screenModelScope.launch {
            rpcServiceManager.awaitAuthentication()
            loadInitialData()
        }

        screenModelScope.launch {
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
        screenModelScope.launch {
            val tags = (_state.value as? AllSongsState.Success)?.tags ?: emptyList()
            val invertTags = (_state.value as? AllSongsState.Success)?.invertTags ?: false
            
            _state.value = AllSongsState.Loading
            try {
                val response = songService.allSongs(0, pageSize, true, tags, invertTags)
                val total = response.total
                val songs = arrayOfNulls<UserSong>(total).toMutableList()

                for (i in response.data.indices) {
                    songs[i] = response.data[i]
                }
                
                _state.value = AllSongsState.Success(
                    songs = songs,
                    total = total,
                    tags = tags,
                    invertTags = invertTags
                )
            } catch (e: Exception) {
                _state.value = AllSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadPage(page: Int) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        if (loadingPages.contains(page)) return
        
        val offset = page * pageSize
        if (offset >= currentState.total) return

        if (currentState.songs.getOrNull(offset) != null) return

        loadingPages.add(page)
        screenModelScope.launch {
            try {
                val response = songService.allSongs(page, pageSize, true, currentState.tags, currentState.invertTags)
                val updatedSongs = currentState.songs.toMutableList()
                
                for (i in response.data.indices) {
                    val index = offset + i
                    if (index < updatedSongs.size) {
                        updatedSongs[index] = response.data[i]
                    }
                }
                
                _state.value = currentState.copy(songs = updatedSongs)
            } catch (_: Exception) {
            } finally {
                loadingPages.remove(page)
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
        refresh()
    }

    fun setInvertTags(invert: Boolean) {
        val currentState = _state.value as? AllSongsState.Success ?: return
        if (currentState.invertTags == invert) return
        
        _state.value = currentState.copy(invertTags = invert)
        refresh()
    }

    fun refresh() {
        loadingPages.clear()
        loadInitialData()
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
