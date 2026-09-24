package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.IImageService
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistScreenModel(
    private val playlistId: PlatformUUID,
    private val isUserPlaylist: Boolean,
    private val rpcServiceManager: RpcServiceManager,
    private val playlistService: IPlaylistService,
    private val userPlaylistService: IUserPlaylistService,
    private val songService: ISongService,
    private val imageService: IImageService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("PlaylistScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { reload() }
    override val isRefreshing = refresher.isRefreshing
    private var generation = 0

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    private val _state = MutableStateFlow<PlaylistState>(PlaylistState.Loading)
    val state = _state.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var hasNextPage = true
    private var isFetching = false

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadPlaylist()
        }
        
        screenModelScope.launch(modelDispatcher) {
            songCache.playlistUpdates.collect { update ->
                when (update) {
                    is PlaylistUpdate.PlaylistContentChanged -> {
                        if (update.playlistId == playlistId) {
                            screenModelScope.launch(modelDispatcher) { reload() }
                        }
                    }
                    else -> {}
                }
            }
        }

        screenModelScope.launch(modelDispatcher) {
            songCache.updates.collect { update ->
                when (update) {
                    is CacheUpdate.SongUpdated -> {
                        val currentState = _state.value
                        if (currentState is PlaylistState.Success) {
                            val updatedSong = update.song
                            val songs = currentState.songs
                            val index = songs.indexOfFirst { it.id == updatedSong.id }
                            if (index != -1) {
                                val newSongs = songs.toMutableList()
                                newSongs[index] = updatedSong
                                _state.value = currentState.copy(songs = newSongs)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun refresh() {
        refresher.refresh()
    }

    private suspend fun reload() {
        val requestGeneration = ++generation
        val previous = _state.value as? PlaylistState.Success
        try {
            val refreshed = coroutineScope {
                if (isUserPlaylist) {
                    val playlistDeferred = async { userPlaylistService.byId(playlistId) }
                    val songsDeferred = async { songService.byUserPlaylist(0, pageSize, playlistId) }
                    val playlist = playlistDeferred.await()
                    val songsResponse = songsDeferred.await()
                    PlaylistState.Success(
                        name = playlist?.name ?: previous?.name ?: "Playlist",
                        imageId = playlist?.imageId ?: previous?.imageId,
                        songs = songsResponse.data,
                        totalDuration = playlist?.totalDuration ?: previous?.totalDuration ?: 0L,
                        isUserPlaylist = isUserPlaylist,
                        hasNextPage = songsResponse.hasNextPage
                    )
                } else {
                    val playlistDeferred = async { playlistService.byId(playlistId) }
                    val songsDeferred = async { songService.byPlaylist(0, pageSize, playlistId) }
                    val playlist = playlistDeferred.await()
                    val songsResponse = songsDeferred.await()
                    PlaylistState.Success(
                        name = playlist?.name ?: previous?.name ?: "Playlist",
                        imageId = playlist?.imageId ?: previous?.imageId,
                        songs = songsResponse.data,
                        totalDuration = playlist?.totalDuration ?: previous?.totalDuration ?: 0L,
                        isUserPlaylist = isUserPlaylist,
                        hasNextPage = songsResponse.hasNextPage
                    )
                }
            }
            if (requestGeneration != generation) return
            _state.value = refreshed
            hasNextPage = refreshed.hasNextPage
            currentPage = if (refreshed.hasNextPage) 1 else 0
            songCache.refreshCached(refreshed.songs)
        } catch (e: Exception) {
            if (previous == null) {
                _state.value = PlaylistState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun setCover(bytes: ByteArray) {
        if (!isUserPlaylist) return
        screenModelScope.launch(modelDispatcher) {
            try {
                val imageId = imageService.createImage(bytes)
                userPlaylistService.setPlaylistImage(playlistId, imageId)
                val currentState = _state.value
                if (currentState is PlaylistState.Success) {
                    _state.value = currentState.copy(imageId = imageId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadPlaylist() {
        if (isFetching) return
        isFetching = true
        val requestGeneration = generation

        screenModelScope.launch(modelDispatcher) {
            if (currentPage == 0) {
                _state.value = PlaylistState.Loading
            }

            try {
                coroutineScope {
                    val currentSongs = if (currentPage == 0) emptyList() else (_state.value as? PlaylistState.Success)?.songs ?: emptyList()
                    val playlistName = (_state.value as? PlaylistState.Success)?.name
                    val playlistImageId = (_state.value as? PlaylistState.Success)?.imageId
                    val playlistDuration = (_state.value as? PlaylistState.Success)?.totalDuration ?: 0L

                    if (isUserPlaylist) {
                        val playlistDeferred = if (currentPage == 0) async { userPlaylistService.byId(playlistId) } else null
                        val songsResponseDeferred = async { songService.byUserPlaylist(currentPage, pageSize, playlistId) }
                        
                        val playlist = playlistDeferred?.await()
                        val songsResponse = songsResponseDeferred.await()
                        if (requestGeneration != generation) return@coroutineScope

                        val name = playlist?.name ?: playlistName ?: "Playlist"
                        val imageId = playlist?.imageId ?: playlistImageId
                        val duration = playlist?.totalDuration ?: playlistDuration
                        
                        _state.value = PlaylistState.Success(
                            name = name,
                            imageId = imageId,
                            songs = currentSongs + songsResponse.data,
                            totalDuration = duration,
                            isUserPlaylist = isUserPlaylist,
                            hasNextPage = songsResponse.hasNextPage
                        )
                        hasNextPage = songsResponse.hasNextPage
                    } else {
                        val playlistDeferred = if (currentPage == 0) async { playlistService.byId(playlistId) } else null
                        val songsResponseDeferred = async { songService.byPlaylist(currentPage, pageSize, playlistId) }
                        
                        val playlist = playlistDeferred?.await()
                        val songsResponse = songsResponseDeferred.await()
                        if (requestGeneration != generation) return@coroutineScope

                        val name = playlist?.name ?: playlistName ?: "Playlist"
                        val imageId = playlist?.imageId ?: playlistImageId
                        val duration = playlist?.totalDuration ?: playlistDuration

                        _state.value = PlaylistState.Success(
                            name = name,
                            imageId = imageId,
                            songs = currentSongs + songsResponse.data,
                            totalDuration = duration,
                            isUserPlaylist = isUserPlaylist,
                            hasNextPage = songsResponse.hasNextPage
                        )
                        hasNextPage = songsResponse.hasNextPage
                    }

                    if (hasNextPage) {
                        currentPage++
                    }
                }
            } catch (e: Exception) {
                if (currentPage == 0 && requestGeneration == generation) {
                    _state.value = PlaylistState.Error(e.message ?: "Unknown error")
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun loadNextPage() {
        if (hasNextPage && !isFetching) {
            loadPlaylist()
        }
    }

    fun playPlaylist() {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId)
            playerModel.playQueue(PlaybackQueue(source = source))
        }
    }

    fun playSong(song: UserSong) {
        val currentState = _state.value
        if (currentState is PlaylistState.Success) {
            val source = PlaybackSource.Playlist(playlistId)
            val index = currentState.songs.indexOf(song)
            playerModel.playQueue(
                PlaybackQueue(source = source),
                startIndex = if (index != -1) index else 0
            )
        }
    }

    sealed class PlaylistState {
        data object Loading : PlaylistState()
        data class Success(
            val name: String, 
            val imageId: PlatformUUID?,
            val songs: List<UserSong>, 
            val totalDuration: Long,
            val isUserPlaylist: Boolean,
            val hasNextPage: Boolean
        ) : PlaylistState()
        data class Error(val message: String) : PlaylistState()
    }
}
