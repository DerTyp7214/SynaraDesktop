package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserProfile
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

data class PlaylistMember(val profile: UserProfile, val access: PlaylistAccess)

data class PlaylistShareState(
    val isLoading: Boolean = true,
    val isPublic: Boolean = false,
    val members: List<PlaylistMember> = emptyList(),
    val candidates: List<UserProfile> = emptyList(),
    val others: List<UserProfile> = emptyList(),
    val filter: String = ""
)

class PlaylistShareModel(
    private val playlistId: PlatformUUID,
    private val userPlaylistService: IUserPlaylistService,
    private val userService: IUserService,
    private val globalState: GlobalStateModel,
    private val songCache: SongCache,
    private val logger: Logger,
    dispatchers: SynaraDispatchers
) {
    private val logTag = LogTag("playlist-share")

    private val modelDispatcher = dispatchers.createNamed("PlaylistShareModel")
    private val scope = CoroutineScope(modelDispatcher + SupervisorJob())

    private val _state = MutableStateFlow(PlaylistShareState())
    val state = _state.asStateFlow()

    @Volatile
    private var profiles: List<UserProfile> = emptyList()

    @Volatile
    private var playlist: UserPlaylist? = null

    fun close() {
        scope.cancel()
        (modelDispatcher as? AutoCloseable)?.close()
    }

    fun load() {
        scope.launch {
            try {
                profiles = userService.allProfiles()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(logTag, "Failed to load the playlist shares", e)
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setFilter(filter: String) {
        _state.update { it.copy(filter = filter) }
        render()
    }

    fun setPublic(isPublic: Boolean) {
        scope.launch {
            change("Failed to change the playlist visibility") { userPlaylistService.setPublic(playlistId, isPublic) }
        }
    }

    fun setShare(userId: PlatformUUID, access: PlaylistAccess) {
        scope.launch {
            change("Failed to share the playlist") {
                userPlaylistService.setShare(playlistId, userId, access)
                true
            }
        }
    }

    fun removeShare(userId: PlatformUUID) {
        scope.launch {
            change("Failed to remove the playlist share") { userPlaylistService.removeShare(playlistId, userId) }
        }
    }

    suspend fun transferOwnership(newOwnerId: PlatformUUID): Boolean = withContext(modelDispatcher) {
        change("Failed to transfer the playlist") { userPlaylistService.transferOwnership(playlistId, newOwnerId) }
    }

    suspend fun leave(): Boolean = withContext(modelDispatcher) {
        val userId = globalState.user.value?.id ?: return@withContext false
        try {
            userPlaylistService.removeShare(playlistId, userId)
            songCache.notifyPlaylistChanged(playlistId)
            songCache.notifyPlaylistsChanged()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(logTag, "Failed to leave the playlist", e)
            false
        }
    }

    private suspend fun change(failure: String, call: suspend () -> Boolean): Boolean {
        return try {
            if (!call()) return false
            reload()
            songCache.notifyPlaylistChanged(playlistId)
            songCache.notifyPlaylistsChanged()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(logTag, failure, e)
            false
        }
    }

    private suspend fun reload() {
        playlist = userPlaylistService.byId(playlistId)
        render()
    }

    private fun render() {
        val current = playlist ?: return
        val access = current.shares.associate { it.userId to it.access }
        _state.update { state ->
            val query = state.filter.trim()
            val others = profiles.filter { profile ->
                profile.id != current.creator &&
                    (profile.username.contains(query, ignoreCase = true) ||
                        profile.displayName?.contains(query, ignoreCase = true) == true)
            }
            state.copy(
                isPublic = current.isPublic,
                members = profiles.mapNotNull { profile -> access[profile.id]?.let { PlaylistMember(profile, it) } },
                candidates = others.filter { it.id !in access },
                others = others
            )
        }
    }
}
