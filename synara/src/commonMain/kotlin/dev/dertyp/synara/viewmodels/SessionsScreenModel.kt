package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Session
import dev.dertyp.services.IPlaybackService
import dev.dertyp.services.ISessionService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.toPlatformUUID
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsState(
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false
)

class SessionsScreenModel(
    private val sessionService: ISessionService,
    private val playbackService: IPlaybackService,
    private val rpcServiceManager: RpcServiceManager,
    private val playerModel: PlayerModel
) : StateScreenModel<SessionsState>(SessionsState()) {

    val currentSessionId: PlatformUUID?
        get() = rpcServiceManager.sessionId?.toPlatformUUID()

    init {
        loadSessions()
    }

    fun loadSessions() {
        screenModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                val sessions = sessionService.getSessions().sortedByDescending { it.lastActive }
                mutableState.update { it.copy(sessions = sessions, isLoading = false) }
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun refreshSessions() {
        screenModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true) }
            try {
                val sessions = sessionService.getSessions().sortedByDescending { it.lastActive }
                mutableState.update { it.copy(sessions = sessions, isRefreshing = false) }
            } catch (_: Exception) {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun deactivateSession(sessionId: PlatformUUID) {
        screenModelScope.launch {
            try {
                sessionService.deactivateSession(sessionId)
                refreshSessions()
            } catch (_: Exception) {}
        }
    }

    fun transferQueue(sessionId: PlatformUUID) {
        screenModelScope.launch {
            try {
                val state = playerModel.getPlaybackState()
                playbackService.setPlaybackState(sessionId, state)
            } catch (_: Exception) {}
        }
    }
}
