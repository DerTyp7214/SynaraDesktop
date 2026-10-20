package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.MoodSummary
import dev.dertyp.data.UserSong
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.mixes.fetch
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MixScreenModel(
    initialSpec: MixSpec,
    private val recommendationService: IRecommendationService,
    private val listeningStatsService: IListeningStatsService,
    private val rpcServiceManager: RpcServiceManager,
    val playerModel: PlayerModel,
    private val dispatchers: SynaraDispatchers
) : StateScreenModel<MixScreenModel.MixState>(MixState(spec = initialSpec, isLoading = true)), Refreshable {

    data class MixState(
        val spec: MixSpec,
        val songs: List<UserSong> = emptyList(),
        val moods: List<MoodSummary> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val isPinned: Boolean = false,
        val isDownloaded: Boolean = false
    )

    private val refresher = RefreshCoalescer(screenModelScope, dispatchers.io) { fetchSongs(state.value.spec) }
    override val isRefreshing = refresher.isRefreshing

    private var loadJob: Job? = null

    init {
        screenModelScope.launch(dispatchers.default) {
            combine(
                state.map { it.spec.key }.distinctUntilChanged(),
                Config.pinnedMixes,
                Config.downloadedMixes
            ) { key, pinned, downloaded ->
                pinned.any { it.key == key } to downloaded.any { it.key == key }
            }.collect { (isPinned, isDownloaded) ->
                mutableState.update { it.copy(isPinned = isPinned, isDownloaded = isDownloaded) }
            }
        }
        load()
        if (initialSpec is MixSpec.YourMix) loadMoods()
    }

    override fun refresh() {
        refresher.refresh()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = screenModelScope.launch(dispatchers.io) {
            mutableState.update { it.copy(isLoading = true, error = null) }
            fetchSongs(state.value.spec)
        }
    }

    private suspend fun fetchSongs(spec: MixSpec) {
        try {
            rpcServiceManager.awaitAuthentication()
            val songs = spec.fetch(recommendationService, listeningStatsService)
            mutableState.update {
                if (it.spec != spec) it else it.copy(songs = songs, isLoading = false, error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update {
                if (it.spec != spec) it
                else it.copy(isLoading = false, error = if (it.songs.isEmpty()) e.message ?: "Unknown error" else null)
            }
        }
    }

    private fun loadMoods() {
        screenModelScope.launch(dispatchers.io) {
            try {
                rpcServiceManager.awaitAuthentication()
                val moods = recommendationService.getMoods()
                mutableState.update { it.copy(moods = moods) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(moods = emptyList()) }
            }
        }
    }

    fun setSpec(spec: MixSpec) {
        if (spec == state.value.spec) return
        Config.setMixOption(spec)
        val pinned = Config.pinnedMixes.value
        if (pinned.any { it.key == spec.key && it != spec }) {
            Config.setPinnedMixes(pinned.map { if (it.key == spec.key) spec else it })
        }
        val downloaded = Config.downloadedMixes.value
        if (downloaded.any { it.key == spec.key && it != spec }) {
            Config.setDownloadedMixes(downloaded.map { if (it.key == spec.key) spec else it })
        }
        mutableState.update { it.copy(spec = spec, songs = emptyList(), error = null) }
        load()
    }

    fun setSize(size: Int) {
        setSpec(
            when (val spec = state.value.spec) {
                is MixSpec.YourMix -> spec.copy(size = size)
                is MixSpec.TopSongs -> spec.copy(size = size)
            }
        )
    }

    fun togglePin() {
        val spec = state.value.spec
        val pinned = Config.pinnedMixes.value
        Config.setPinnedMixes(
            if (pinned.any { it.key == spec.key }) pinned.filterNot { it.key == spec.key } else pinned + spec
        )
    }

    fun toggleDownload() {
        val spec = state.value.spec
        if (Config.pinnedMixes.value.none { it.key == spec.key }) return
        val downloaded = Config.downloadedMixes.value
        Config.setDownloadedMixes(
            if (downloaded.any { it.key == spec.key }) downloaded.filterNot { it.key == spec.key } else downloaded + spec
        )
    }

    fun play(index: Int = 0) {
        val songs = state.value.songs
        if (index !in songs.indices) return
        playerModel.playQueue(PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) }), startIndex = index)
    }

    fun shuffle() {
        val songs = state.value.songs
        if (songs.isEmpty()) return
        playerModel.setShuffleMode(true)
        playerModel.playQueue(PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) }), startIndex = songs.indices.random())
    }

    fun saveAsPlaylist(name: String) {
        val songs = state.value.songs
        if (songs.isEmpty()) return
        playerModel.createPlaylist(name, PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) }))
    }
}
