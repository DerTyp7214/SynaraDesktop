package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.CacheUpdate
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ArtistLikedSongsScreenModel(
    private val artistId: PlatformUUID,
    private val rpcServiceManager: RpcServiceManager,
    private val artistService: IArtistService,
    private val songService: ISongService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("ArtistLikedSongsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    override fun refresh() {
        refresher.refresh()
    }

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    private val _state = MutableStateFlow<ArtistLikedSongsState>(ArtistLikedSongsState.Loading())
    val state = _state.asStateFlow()

    private var currentPage = 0
    private var pageShift = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadArtist()
            loadSongs()
        }

        screenModelScope.launch(modelDispatcher) {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> applySongUpdate(update.song)
                }
            }
        }
    }

    private fun applySongUpdate(updatedSong: UserSong) {
        val currentState = _state.value as? ArtistLikedSongsState.Success ?: return
        val index = currentState.songs.indexOfFirst { it.id == updatedSong.id }
        if (index == -1) return
        val wasLiked = currentState.songs[index].isFavourite == true
        val isLiked = updatedSong.isFavourite == true
        if (wasLiked != isLiked) {
            pageShift += if (isLiked) 1 else -1
            ++generation
        }
        val songs = currentState.songs.toMutableList()
        songs[index] = updatedSong
        _state.value = currentState.copy(songs = songs)
    }

    private fun loadArtist() {
        screenModelScope.launch(modelDispatcher) {
            try {
                val artist = artistService.byId(artistId)
                _state.update { currentState ->
                    when (currentState) {
                        is ArtistLikedSongsState.Success -> currentState.copy(artist = artist)
                        is ArtistLikedSongsState.Loading -> currentState.copy(artist = artist)
                        else -> currentState
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private suspend fun reload() {
        val requestGeneration = ++generation
        try {
            coroutineScope {
                val artistDeferred = async { artistService.byId(artistId) }
                val songsDeferred = async { songService.likedByArtist(0, pageSize, artistId, true) }
                val artist = artistDeferred.await()
                val response = songsDeferred.await()
                if (requestGeneration != generation) return@coroutineScope
                ++generation
                _state.value = ArtistLikedSongsState.Success(
                    artist = artist,
                    songs = response.data,
                    hasNextPage = response.hasNextPage
                )
                hasNextPage = response.hasNextPage
                currentPage = if (response.hasNextPage) 1 else 0
                pageShift = 0
                songCache.refreshCached(response.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestGeneration == generation && _state.value !is ArtistLikedSongsState.Success) {
                _state.value = ArtistLikedSongsState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadSongs() {
        screenModelScope.launch(modelDispatcher) {
            if (isFetching || !hasNextPage) return@launch
            isFetching = true
            val requestGeneration = generation
            val firstPage = currentPage == 0
            val offset = if (firstPage) 0 else (currentPage * pageSize + pageShift).coerceAtLeast(0)
            val page = offset / pageSize
            var dropped = false
            try {
                val response = songService.likedByArtist(page, pageSize, artistId, true)
                if (requestGeneration != generation) {
                    dropped = !firstPage
                    return@launch
                }

                _state.update { currentState ->
                    val currentSongs = (currentState as? ArtistLikedSongsState.Success)?.songs ?: emptyList()
                    val artist = (currentState as? ArtistLikedSongsState.Success)?.artist
                        ?: (currentState as? ArtistLikedSongsState.Loading)?.artist
                    val known = currentSongs.mapTo(HashSet()) { it.id }

                    ArtistLikedSongsState.Success(
                        artist = artist,
                        songs = currentSongs + response.data.drop(offset % pageSize).filter { it.id !in known },
                        hasNextPage = response.hasNextPage
                    )
                }

                hasNextPage = response.hasNextPage
                pageShift = 0
                if (hasNextPage) {
                    currentPage = page + 1
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstPage && requestGeneration == generation) {
                    _state.value = ArtistLikedSongsState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
                if (dropped) loadSongs()
            }
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is ArtistLikedSongsState.Success) {
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = PlaybackSource.Artist(artistId)),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class ArtistLikedSongsState {
        data class Loading(val artist: Artist? = null) : ArtistLikedSongsState()
        data class Success(
            val artist: Artist?,
            val songs: List<UserSong>,
            val hasNextPage: Boolean
        ) : ArtistLikedSongsState()
        data class Error(val message: String) : ArtistLikedSongsState()
    }
}
