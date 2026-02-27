package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.data.ServerStats
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.ServerStatsServiceWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeScreenModel(
    private val rpcServiceManager: RpcServiceManager,
    private val serverStatsService: ServerStatsServiceWrapper,
    val globalState: GlobalStateModel
) : ScreenModel {

    private val _serverStats = MutableStateFlow<ServerStats?>(null)
    val serverStats = _serverStats.asStateFlow()

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
