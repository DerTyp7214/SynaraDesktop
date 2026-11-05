package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.player.PlaybackQueue
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

data class ArtistTopSongsState(
    val artist: Artist? = null,
    val order: ArtistTopSongOrder = ArtistTopSongOrder.GLOBAL_LISTENS,
    val songs: List<ArtistTopSong> = emptyList(),
    val songsOrder: ArtistTopSongOrder = order,
    val isLoading: Boolean = true,
    val error: String? = null
)

class ArtistTopSongsScreenModel(
    private val artistId: PlatformUUID,
    initialOrder: ArtistTopSongOrder,
    private val rpcServiceManager: RpcServiceManager,
    private val artistService: IArtistService,
    private val songCache: SongCache,
    val playerModel: PlayerModel,
    dispatchers: SynaraDispatchers
) : StateScreenModel<ArtistTopSongsState>(ArtistTopSongsState(order = initialOrder)), Refreshable {

    private val modelDispatcher = dispatchers.createNamed("ArtistTopSongsScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) { load() }
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
            load()
        }
    }

    private suspend fun load() {
        val order = state.value.order
        try {
            coroutineScope {
                val artistDeferred = async { artistService.byId(artistId) }
                val songsDeferred = async { artistService.topSongs(artistId, LIMIT, order) }
                val artist = artistDeferred.await()
                val songs = songsDeferred.await()
                mutableState.update {
                    if (it.order != order) it.copy(artist = artist)
                    else it.copy(artist = artist, songs = songs, songsOrder = order, isLoading = false, error = null)
                }
                songCache.refreshCached(songs.map { it.song })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update {
                if (it.order != order) it
                else it.copy(isLoading = false, error = if (it.songs.isEmpty()) e.message ?: "Unknown error" else null)
            }
        }
    }

    fun setOrder(order: ArtistTopSongOrder) {
        if (state.value.order == order) return
        mutableState.update { it.copy(order = order, isLoading = true, error = null) }
        screenModelScope.launch(modelDispatcher) { load() }
    }

    fun play(index: Int = 0) {
        val songs = state.value.songs
        if (index !in songs.indices) return
        playerModel.playQueue(PlaybackQueue(items = songs.map { QueueEntry.Explicit(it.song) }), startIndex = index)
    }

    fun shuffle() {
        val songs = state.value.songs
        if (songs.isEmpty()) return
        playerModel.setShuffleMode(true)
        playerModel.playQueue(PlaybackQueue(items = songs.map { QueueEntry.Explicit(it.song) }), startIndex = songs.indices.random())
    }

    private companion object {
        const val LIMIT = 100
    }
}
