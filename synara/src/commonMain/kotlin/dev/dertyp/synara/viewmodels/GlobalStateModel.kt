package dev.dertyp.synara.viewmodels

import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlaylistUpdate
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.services.UserPlaylistServiceWrapper
import dev.dertyp.synara.rpc.services.UserServiceWrapper
import dev.dertyp.synara.scrobble.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class GlobalStateModel(
    private val userService: UserServiceWrapper,
    private val userPlaylistService: UserPlaylistServiceWrapper,
    private val scrobblerService: ScrobblerService,
    private val songCache: SongCache
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _user = MutableStateFlow<User?>(null)
    val user = _user.asStateFlow()

    private val _userPlaylists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val userPlaylists = _userPlaylists.asStateFlow()

    private val _isRefreshingPlaylists = MutableStateFlow(false)
    val isRefreshingPlaylists = _isRefreshingPlaylists.asStateFlow()

    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded = _isPlayerExpanded.asStateFlow()

    private val _isQueueExpanded = MutableStateFlow(false)
    val isQueueExpanded = _isQueueExpanded.asStateFlow()

    private val _isLyricsExpanded = MutableStateFlow(false)
    val isLyricsExpanded = _isLyricsExpanded.asStateFlow()

    init {
        refreshUser()
        refreshPlaylists()

        scrobblerService.registerScrobbler(LocalSongScrobbler::class)
        
        scope.launch {
            Config.isListenBrainzEnabled.collectLatest { enabled ->
                if (enabled) {
                    scrobblerService.registerScrobbler(ListenBrainzScrobbler::class)
                } else {
                    scrobblerService.unregisterScrobbler(ListenBrainzScrobbler::class)
                }
            }
        }
        
        scope.launch {
            Config.isLastFmEnabled.collectLatest { enabled ->
                if (enabled) {
                    scrobblerService.registerScrobbler(LastFmScrobbler::class)
                } else {
                    scrobblerService.unregisterScrobbler(LastFmScrobbler::class)
                }
            }
        }

        scope.launch {
            Config.isDiscordRpcEnabled.collectLatest { enabled ->
                if (enabled) {
                    scrobblerService.registerScrobbler(DiscordScrobbler::class)
                } else {
                    scrobblerService.unregisterScrobbler(DiscordScrobbler::class)
                }
            }
        }

        scope.launch {
            songCache.playlistUpdates
                .filterIsInstance<PlaylistUpdate.PlaylistsReloadRequired>()
                .collect {
                    refreshPlaylists()
                }
        }

        scrobblerService.start()
    }

    fun refreshUser() {
        scope.launch {
            try {
                _user.value = userService.me()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshPlaylists() {
        scope.launch {
            _isRefreshingPlaylists.value = true
            try {
                val currentUser = _user.value ?: userService.me().also { _user.value = it }
                val response = userPlaylistService.allPlaylists(currentUser.id, 0, 100)
                _userPlaylists.value = response.data
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshingPlaylists.value = false
            }
        }
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
        if (!expanded) {
            _isQueueExpanded.value = false
            _isLyricsExpanded.value = false
        }
    }

    fun togglePlayerExpanded() {
        setPlayerExpanded(!_isPlayerExpanded.value)
    }

    fun setQueueExpanded(expanded: Boolean) {
        _isQueueExpanded.value = expanded
        if (expanded) {
            _isPlayerExpanded.value = true
            _isLyricsExpanded.value = false
        }
    }

    fun toggleQueueExpanded() {
        setQueueExpanded(!_isQueueExpanded.value)
    }

    fun setLyricsExpanded(expanded: Boolean) {
        _isLyricsExpanded.value = expanded
        if (expanded) {
            _isPlayerExpanded.value = true
            _isQueueExpanded.value = false
        }
    }

    fun toggleLyricsExpanded() {
        setLyricsExpanded(!_isLyricsExpanded.value)
    }
}
