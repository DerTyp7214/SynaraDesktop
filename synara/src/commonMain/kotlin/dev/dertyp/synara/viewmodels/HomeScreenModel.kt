package dev.dertyp.synara.viewmodels

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ServerStats
import dev.dertyp.data.UserSong
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class HomeScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val serverStatsService: ServerStatsServiceWrapper,
    val globalState: GlobalStateModel,
    database: SynaraDatabase,
    private val json: Json,
) : ScreenModel {

    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats = _serverStats.asStateFlow()

    private val recentlyPlayedQueries = database.recentlyPlayedQueries

    val recentSongs = recentlyPlayedQueries.getSongs(10)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { list -> list.map { json.decodeFromString<UserSong>(it.payload) } }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    val recentAlbums = recentlyPlayedQueries.getAlbums(15)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { list -> list.map { json.decodeFromString<Album>(it.payload) } }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    val recentArtists = recentlyPlayedQueries.getArtists(15)
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { list -> list.map { json.decodeFromString<Artist>(it.payload) } }
        .stateIn(screenModelScope, SharingStarted.Eagerly, emptyList())

    init {
        loadStats()
    }

    fun loadStats() {
        screenModelScope.launch {
            try {
                _serverStats.value = serverStatsService.getStats()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun logout() {
        screenModelScope.launch {
            rpcServiceManager.handleAuthFailure()
        }
    }

    fun toggleDarkMode() {
        Config.setDarkTheme(!Config.darkTheme.value)
    }
}
