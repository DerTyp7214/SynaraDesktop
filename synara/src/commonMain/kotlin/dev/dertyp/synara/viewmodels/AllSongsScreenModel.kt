package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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
            _state.value = AllSongsState.Loading
            try {
                val response = songService.allSongs(0, pageSize, true)
                val total = response.total
                val songs = arrayOfNulls<UserSong>(total).toMutableList()

                for (i in response.data.indices) {
                    songs[i] = response.data[i]
                }
                
                _state.value = AllSongsState.Success(
                    songs = songs,
                    total = total
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
                val response = songService.allSongs(page, pageSize, true)
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

    fun refresh() {
        loadingPages.clear()
        loadInitialData()
    }

    fun playAll() {
        playerModel.playQueue(PlaybackQueue(source = PlaybackSource.AllSongs))
    }

    fun playSong(song: UserSong, index: Int) {
        playerModel.playQueue(
            PlaybackQueue(source = PlaybackSource.AllSongs),
            startIndex = index
        )
    }

    sealed class AllSongsState {
        data object Loading : AllSongsState()
        data class Success(val songs: List<UserSong?>, val total: Int) : AllSongsState()
        data class Error(val message: String) : AllSongsState()
    }
}
