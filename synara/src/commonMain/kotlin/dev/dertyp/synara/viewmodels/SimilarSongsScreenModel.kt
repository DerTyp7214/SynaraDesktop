package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.IDiscoveryService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.ui.components.dialogs.DiscoveryCriterion
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsSeed
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SimilarSongsScreenModel(
    private val seed: SimilarSongsSeed,
    private val criterion: DiscoveryCriterion,
    private val limit: Int,
    private val discoveryService: IDiscoveryService,
    private val songService: ISongService,
    private val rpcServiceManager: RpcServiceManager,
    val playerModel: PlayerModel,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<SimilarSongsScreenModel.SimilarSongsState>(SimilarSongsState()) {

    data class SimilarSongsState(
        val seedSongs: List<UserSong> = emptyList(),
        val songs: List<UserSong> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init {
        loadSimilarSongs()
    }

    private fun loadSimilarSongs() {
        screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                rpcServiceManager.awaitAuthentication()

                val seedSongs = when (seed) {
                    is SimilarSongsSeed.Songs -> songService.byIds(seed.songIds)
                    is SimilarSongsSeed.Playlist -> songService.byUserPlaylist(0, 50, seed.playlistId).data
                    is SimilarSongsSeed.Album -> songService.byAlbum(0, 50, seed.albumId).data
                }

                val songs = when (seed) {
                    is SimilarSongsSeed.Songs -> fetchBySongs(seed.songIds)
                    is SimilarSongsSeed.Playlist -> fetchByPlaylist(seed.playlistId)
                    is SimilarSongsSeed.Album -> fetchByAlbum(seed.albumId)
                }
                
                mutableState.update { it.copy(seedSongs = seedSongs, songs = songs, isLoading = false) }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private suspend fun fetchBySongs(songIds: List<PlatformUUID>): List<UserSong> {
        return when (criterion) {
            DiscoveryCriterion.Default -> discoveryService.getSimilarSongs(songIds, limit)
            DiscoveryCriterion.Bpm -> discoveryService.getSimilarSongsByBpm(songIds, limit)
            DiscoveryCriterion.Energy -> discoveryService.getSimilarSongsByEnergy(songIds, limit)
            DiscoveryCriterion.Mood -> discoveryService.getSimilarSongsByMood(songIds, limit)
            DiscoveryCriterion.Composers -> discoveryService.getSongsBySameComposers(songIds, limit)
            DiscoveryCriterion.Lyricists -> discoveryService.getSongsBySameLyricists(songIds, limit)
            DiscoveryCriterion.Producers -> discoveryService.getSongsBySameProducers(songIds, limit)
        }
    }

    private suspend fun fetchByPlaylist(playlistId: PlatformUUID): List<UserSong> {
        return if (criterion == DiscoveryCriterion.Default) {
            discoveryService.getSimilarSongsByPlaylist(playlistId, limit)
        } else {
            val songIds = songService.songIdsByUserPlaylist(playlistId).toList()
            fetchBySongs(songIds)
        }
    }

    private suspend fun fetchByAlbum(albumId: PlatformUUID): List<UserSong> {
        val songIds = songService.songIdsByAlbum(albumId).toList()
        return fetchBySongs(songIds)
    }

    fun createPlaylist(name: String) {
        val songs = state.value.songs
        if (songs.isNotEmpty()) {
            playerModel.createPlaylist(name, PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) }))
        }
    }
}
