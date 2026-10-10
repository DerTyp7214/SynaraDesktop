package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ServerStats
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.RECENTLY_PLAYED_ALBUMS_LIMIT
import dev.dertyp.synara.db.RECENTLY_PLAYED_ARTISTS_LIMIT
import dev.dertyp.synara.db.RECENTLY_PLAYED_SONGS_LIMIT
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import dev.dertyp.synara.ui.models.userMessageResource
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

private val HOME_SCREEN_TAG = LogTag("home-screen")

sealed class ServerStatsState {
    data object Loading : ServerStatsState()
    data class Loaded(val stats: ServerStats) : ServerStatsState()
    data class Failed(val message: StringResource) : ServerStatsState()
}

class HomeScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val serverStatsService: ServerStatsServiceWrapper,
    val globalState: GlobalStateModel,
    private val dispatchers: SynaraDispatchers,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val logger: Logger,
) : ScreenModel, Refreshable {

    private val modelDispatcher = dispatchers.createNamed("HomeScreenModel")

    private val refresher = RefreshCoalescer(screenModelScope, modelDispatcher) {
        fetchStats()
        _refreshKey.value++
    }
    override val isRefreshing = refresher.isRefreshing

    private val _refreshKey = MutableStateFlow(0)
    val refreshKey = _refreshKey.asStateFlow()

    override fun refresh() {
        refresher.refresh()
    }

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    private val _serverStats = MutableStateFlow<ServerStatsState>(ServerStatsState.Loading)
    val serverStats = _serverStats.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSongs = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getSongsFlow(userId, RECENTLY_PLAYED_SONGS_LIMIT)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentAlbums = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getAlbumsFlow(userId, RECENTLY_PLAYED_ALBUMS_LIMIT)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentArtists = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getArtistsFlow(userId, RECENTLY_PLAYED_ARTISTS_LIMIT)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadStats()
        }
    }

    fun loadStats() {
        if (_serverStats.value is ServerStatsState.Failed) _serverStats.value = ServerStatsState.Loading
        screenModelScope.launch(modelDispatcher) {
            fetchStats()
        }
    }

    private suspend fun fetchStats() {
        try {
            _serverStats.value = ServerStatsState.Loaded(serverStatsService.getStats())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(HOME_SCREEN_TAG, "Loading the server stats failed", e)
            if (_serverStats.value !is ServerStatsState.Loaded) _serverStats.value = ServerStatsState.Failed(e.userMessageResource())
        }
    }

    fun logout() {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.handleAuthFailure(null)
        }
    }

    fun toggleDarkMode() {
        Config.setDarkTheme(!Config.darkTheme.value)
    }
}
