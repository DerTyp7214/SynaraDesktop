package dev.dertyp.synara.viewmodels

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ServerStats
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.AlbumServiceWrapper
import dev.dertyp.synara.rpc.services.ArtistServiceWrapper
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import dev.dertyp.synara.rpc.services.SongServiceWrapper
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.toPlatformUUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val serverStatsService: ServerStatsServiceWrapper,
    private val songService: SongServiceWrapper,
    private val albumService: AlbumServiceWrapper,
    private val artistService: ArtistServiceWrapper,
    val globalState: GlobalStateModel,
    private val dispatchers: SynaraDispatchers,
    database: SynaraDatabase,
) : ScreenModel {

    private val modelDispatcher = dispatchers.createNamed("HomeScreenModel")

    override fun onDispose() {
        super.onDispose()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats = _serverStats.asStateFlow()

    private val recentlyPlayedQueries = database.recentlyPlayedQueries

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSongs = globalState.user.flatMapLatest { user ->
        val userId = user?.id?.toString() ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedQueries.getSongs(userId, 10)
            .asFlow()
            .mapToList(modelDispatcher)
            .map { list ->
                val ids = list.map { it.id.toPlatformUUID() }
                if (ids.isEmpty()) return@map emptyList()
                val songs = songService.byIds(ids).data
                ids.mapNotNull { id -> songs.find { it.id == id } }
            }
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentAlbums = globalState.user.flatMapLatest { user ->
        val userId = user?.id?.toString() ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedQueries.getAlbums(userId, 15)
            .asFlow()
            .mapToList(modelDispatcher)
            .map { list ->
                val ids = list.map { it.id.toPlatformUUID() }
                if (ids.isEmpty()) return@map emptyList()
                val albums = albumService.byIds(ids)
                ids.mapNotNull { id -> albums.find { it.id == id } }
            }
    }.stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentArtists = globalState.user.flatMapLatest { user ->
        val userId = user?.id?.toString() ?: return@flatMapLatest flowOf(emptyList())
        recentlyPlayedQueries.getArtists(userId, 15)
            .asFlow()
            .mapToList(modelDispatcher)
            .map { list ->
                val ids = list.map { it.id.toPlatformUUID() }
                if (ids.isEmpty()) return@map emptyList()
                val artists = artistService.byIds(ids)
                ids.mapNotNull { id -> artists.find { it.id == id } }
            }
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
