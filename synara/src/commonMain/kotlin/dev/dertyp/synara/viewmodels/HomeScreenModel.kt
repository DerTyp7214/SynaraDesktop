package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ServerStats
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val serverStatsService: ServerStatsServiceWrapper,
    val globalState: GlobalStateModel,
    private val dispatchers: SynaraDispatchers,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("HomeScreenModel")

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats = _serverStats.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSongs = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getSongsFlow(userId, 10)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentAlbums = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getAlbumsFlow(userId, 15)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentArtists = globalState.user.flatMapLatest { user ->
        val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedRepository.getArtistsFlow(userId, 15)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    init {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.awaitAuthentication()
            loadStats()
        }
    }

    fun loadStats() {
        screenModelScope.launch(modelDispatcher) {
            try {
                _serverStats.value = serverStatsService.getStats()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun logout() {
        screenModelScope.launch(modelDispatcher) {
            rpcServiceManager.handleAuthFailure()
        }
    }

    fun toggleDarkMode() {
        Config.setDarkTheme(!Config.darkTheme.value)
    }
}
