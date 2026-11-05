package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistState(
    val artist: Artist? = null,
    val topSongs: List<UserSong> = emptyList(),
    val topLikedSongs: List<UserSong> = emptyList(),
    val popularSongs: List<ArtistTopSong> = emptyList(),
    val popularOrder: ArtistTopSongOrder = ArtistTopSongOrder.GLOBAL_LISTENS,
    val popularSongsOrder: ArtistTopSongOrder = ArtistTopSongOrder.GLOBAL_LISTENS,
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val isMerging: Boolean = false,
    val isSplitting: Boolean = false
)

class ArtistScreenModel(
    private val artistId: PlatformUUID,
    private val rpcServiceManager: RpcServiceManager,
    private val artistService: IArtistService,
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : StateScreenModel<ArtistState>(ArtistState()), Refreshable {

    private val modelDispatcher = dispatchers.createNamed("ArtistScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { fetchArtist() }
    override val isRefreshing = refresher.isRefreshing

    override fun refresh() {
        refresher.refresh()
    }

    override fun onDispose() {
        (modelDispatcher as? AutoCloseable)?.close()
        super.onDispose()
    }

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadArtist()
        }
    }

    private fun loadArtist() {
        screenModelScope.launch(modelDispatcher) {
            mutableState.update { it.copy(isLoading = true) }
            try {
                fetchArtist()
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun fetchArtist() {
        coroutineScope {
            val artistDeferred = async { artistService.byId(artistId) }
            val songsDeferred = async { songService.byArtist(0, 5, artistId) }
            val albumsDeferred = async { albumService.byArtist(0, 10, artistId, explicit = true) }
            val topLikedSongsDeferred = async { songService.likedByArtist(0, 5, artistId, true) }
            val popularOrder = state.value.popularOrder
            val popularDeferred = async { fetchPopular(popularOrder) }

            val artist = artistDeferred.await()
            val songsResponse = songsDeferred.await()
            val albumsResponse = albumsDeferred.await()
            val topLikedSongsResponse = topLikedSongsDeferred.await()
            val popular = popularDeferred.await()

            mutableState.update {
                it.copy(
                    artist = artist,
                    topSongs = songsResponse.data,
                    topLikedSongs = topLikedSongsResponse.data,
                    popularSongs = if (popular != null && it.popularOrder == popularOrder) popular else it.popularSongs,
                    popularSongsOrder = if (popular != null && it.popularOrder == popularOrder) popularOrder else it.popularSongsOrder,
                    albums = albumsResponse.data,
                    isLoading = false
                )
            }
            songCache.refreshCached(songsResponse.data + topLikedSongsResponse.data + popular.orEmpty().map { it.song })
        }
    }

    private suspend fun fetchPopular(order: ArtistTopSongOrder): List<ArtistTopSong>? {
        return try {
            artistService.topSongs(artistId, POPULAR_LIMIT, order)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun setPopularOrder(order: ArtistTopSongOrder) {
        if (state.value.popularOrder == order) return
        mutableState.update { it.copy(popularOrder = order) }
        screenModelScope.launch(modelDispatcher) {
            val popular = fetchPopular(order) ?: return@launch
            mutableState.update { if (it.popularOrder == order) it.copy(popularSongs = popular, popularSongsOrder = order) else it }
            songCache.refreshCached(popular.map { it.song })
        }
    }

    fun playPopular(index: Int) {
        val songs = state.value.popularSongs
        if (index !in songs.indices) return
        playerModel.playQueue(PlaybackQueue(items = songs.map { QueueEntry.Explicit(it.song) }), startIndex = index)
    }

    fun playArtist(startIndex: Int = 0) {
        playerModel.playQueue(
            PlaybackQueue(
                source = PlaybackSource.Artist(artistId)
            ),
            startIndex = startIndex
        )
    }

    fun playSong(song: UserSong) {
        val index = state.value.topSongs.indexOf(song)
        if (index != -1) {
            playerModel.playQueue(
                PlaybackQueue(
                    source = PlaybackSource.Artist(artistId)
                ),
                startIndex = index
            )
        } else {
            playerModel.playNext(song)
            playerModel.skipNext()
        }
    }

    fun playNext(song: UserSong) {
        playerModel.playNext(song)
    }

    private companion object {
        const val POPULAR_LIMIT = 5
    }
}
