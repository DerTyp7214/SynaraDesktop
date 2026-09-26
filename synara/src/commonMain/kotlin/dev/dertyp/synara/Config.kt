package dev.dertyp.synara

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.russhwolf.settings.Settings
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.VisualizerPreset
import dev.dertyp.synara.settings.VisualizerPresets
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.theme.createColorSchemeFromSeeds
import dev.dertyp.synara.ui.IconPackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object Config : KoinComponent {
    private val settings: Settings by inject()

    private val _darkTheme = MutableStateFlow(settings.getBoolean("dark_theme", true))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _iconStyle = MutableStateFlow(settings.getString("icon_style", "rounded"))
    val iconStyle: StateFlow<String> = _iconStyle.asStateFlow()

    private val _iconFilled = MutableStateFlow(settings.get(SettingKey.IconFilled, false))
    val iconFilled: StateFlow<Boolean> = _iconFilled.asStateFlow()

    private val _iconPack = MutableStateFlow(
        try {
            IconPackType.valueOf(settings.getString("icon_pack", IconPackType.MaterialSymbols.name))
        } catch (_: Exception) {
            IconPackType.MaterialSymbols
        }
    )
    val iconPack: StateFlow<IconPackType> = _iconPack.asStateFlow()

    private val _showRemainingTime = MutableStateFlow(settings.get(SettingKey.ShowRemainingTime, false))
    val showRemainingTime: StateFlow<Boolean> = _showRemainingTime.asStateFlow()

    private val _showTitleTagsInText = MutableStateFlow(settings.get(SettingKey.ShowTitleTagsInText, true))
    val showTitleTagsInText: StateFlow<Boolean> = _showTitleTagsInText.asStateFlow()

    private val _language = MutableStateFlow(settings.getStringOrNull("language"))
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _lightThemeColor = MutableStateFlow(Color(settings.get(SettingKey.LightThemeColor, Color.Green.toArgb())))
    val lightThemeColor: StateFlow<Color> = _lightThemeColor.asStateFlow()

    private val _darkThemeColor = MutableStateFlow(Color(settings.get(SettingKey.DarkThemeColor, Color.Red.toArgb())))
    val darkThemeColor: StateFlow<Color> = _darkThemeColor.asStateFlow()

    private val _useSongColor = MutableStateFlow(settings.get(SettingKey.UseSongColor, false))
    val useSongColor: StateFlow<Boolean> = _useSongColor.asStateFlow()

    private val _usePywal = MutableStateFlow(settings.get(SettingKey.UsePywal, false))
    val usePywal: StateFlow<Boolean> = _usePywal.asStateFlow()

    private val _darkColorScheme = MutableStateFlow(createColorSchemeFromSeeds(Triple(_darkThemeColor.value.toArgb(), null, null), true))
    val darkColorScheme: StateFlow<ColorScheme> = _darkColorScheme.asStateFlow()

    private val _lightColorScheme = MutableStateFlow(createColorSchemeFromSeeds(Triple(_lightThemeColor.value.toArgb(), null, null), false))
    val lightColorScheme: StateFlow<ColorScheme> = _lightColorScheme.asStateFlow()

    // Scrobbling
    private val _isServerScrobblingEnabled = MutableStateFlow(settings.get(SettingKey.IsServerScrobblingEnabled, true))
    val isServerScrobblingEnabled: StateFlow<Boolean> = _isServerScrobblingEnabled.asStateFlow()

    private val _isListenBrainzEnabled = MutableStateFlow(settings.get(SettingKey.IsListenBrainzEnabled, false))
    val isListenBrainzEnabled: StateFlow<Boolean> = _isListenBrainzEnabled.asStateFlow()

    private val _listenBrainzToken = MutableStateFlow(settings.getOrNull(SettingKey.ListenBrainzToken) ?: "")
    val listenBrainzToken: StateFlow<String> = _listenBrainzToken.asStateFlow()

    private val _isLastFmEnabled = MutableStateFlow(settings.get(SettingKey.IsLastFmEnabled, false))
    val isLastFmEnabled: StateFlow<Boolean> = _isLastFmEnabled.asStateFlow()

    private val _lastFmApiKey = MutableStateFlow(settings.getOrNull(SettingKey.LastFmApiKey) ?: "")
    val lastFmApiKey: StateFlow<String> = _lastFmApiKey.asStateFlow()

    private val _lastFmSharedSecret = MutableStateFlow(settings.getOrNull(SettingKey.LastFmSharedSecret) ?: "")
    val lastFmSharedSecret: StateFlow<String> = _lastFmSharedSecret.asStateFlow()

    private val _lastFmSessionKey = MutableStateFlow(settings.getOrNull(SettingKey.LastFmSessionKey) ?: "")
    val lastFmSessionKey: StateFlow<String> = _lastFmSessionKey.asStateFlow()

    private val _lastFmUsername = MutableStateFlow(settings.getOrNull(SettingKey.LastFmUsername) ?: "")
    val lastFmUsername: StateFlow<String> = _lastFmUsername.asStateFlow()

    private val _isDiscordRpcEnabled = MutableStateFlow(settings.get(SettingKey.IsDiscordRpcEnabled, false))
    val isDiscordRpcEnabled: StateFlow<Boolean> = _isDiscordRpcEnabled.asStateFlow()

    // Queue sync
    private val _isQueueSyncEnabled = MutableStateFlow(settings.get(SettingKey.IsQueueSyncEnabled, false))
    val isQueueSyncEnabled: StateFlow<Boolean> = _isQueueSyncEnabled.asStateFlow()

    private val _queueSyncDeviceName = MutableStateFlow(settings.getOrNull(SettingKey.QueueSyncDeviceName) ?: "")
    val queueSyncDeviceName: StateFlow<String> = _queueSyncDeviceName.asStateFlow()

    // Remote control
    private val _isRemoteControlEnabled = MutableStateFlow(settings.get(SettingKey.IsRemoteControlEnabled, false))
    val isRemoteControlEnabled: StateFlow<Boolean> = _isRemoteControlEnabled.asStateFlow()

    private val _remoteControlOptInShown = MutableStateFlow(settings.get(SettingKey.RemoteControlOptInShown, false))
    val remoteControlOptInShown: StateFlow<Boolean> = _remoteControlOptInShown.asStateFlow()

    // Podcasts
    private val _isPodcastsEnabled = MutableStateFlow(settings.get(SettingKey.IsPodcastsEnabled, false))
    val isPodcastsEnabled: StateFlow<Boolean> = _isPodcastsEnabled.asStateFlow()

    private val _podcastPlaybackSpeed = MutableStateFlow(settings.get(SettingKey.PodcastPlaybackSpeed, 1f))
    val podcastPlaybackSpeed: StateFlow<Float> = _podcastPlaybackSpeed.asStateFlow()

    private val _podcastOptInShown = MutableStateFlow(settings.get(SettingKey.PodcastOptInShown, false))
    val podcastOptInShown: StateFlow<Boolean> = _podcastOptInShown.asStateFlow()

    // Settings sync
    private val _isSettingsSyncEnabled = MutableStateFlow(settings.get(SettingKey.IsSettingsSyncEnabled, false))
    val isSettingsSyncEnabled: StateFlow<Boolean> = _isSettingsSyncEnabled.asStateFlow()

    private val _isSecretsSyncEnabled = MutableStateFlow(settings.get(SettingKey.IsSecretsSyncEnabled, false))
    val isSecretsSyncEnabled: StateFlow<Boolean> = _isSecretsSyncEnabled.asStateFlow()

    private val _syncSetupShown = MutableStateFlow(settings.get(SettingKey.SyncSetupShown, false))
    val syncSetupShown: StateFlow<Boolean> = _syncSetupShown.asStateFlow()

    // Visualizer
    private val _particleMultiplier = MutableStateFlow(settings.get(SettingKey.ParticleMultiplier, 2.5f))
    val particleMultiplier: StateFlow<Float> = _particleMultiplier.asStateFlow()

    private val _userVisualizerPresets = MutableStateFlow(
        VisualizerPresets.decode(settings.getOrNull(SettingKey.VisualizerPresets))
    )
    val userVisualizerPresets: StateFlow<List<VisualizerPreset>> = _userVisualizerPresets.asStateFlow()

    private val _visualizerPresets = MutableStateFlow(VisualizerPresets.builtIns + _userVisualizerPresets.value)
    val visualizerPresets: StateFlow<List<VisualizerPreset>> = _visualizerPresets.asStateFlow()

    private val _activeVisualizerPresetId = MutableStateFlow(
        VisualizerPresets.resolveActiveId(
            settings.getOrNull(SettingKey.VisualizerActivePreset),
            settings.getOrNull(SettingKey.VisualizerStyle),
            _userVisualizerPresets.value
        )
    )
    val activeVisualizerPresetId: StateFlow<String> = _activeVisualizerPresetId.asStateFlow()

    private val _activeVisualizerPreset = MutableStateFlow(
        findVisualizerPreset(_visualizerPresets.value, _activeVisualizerPresetId.value)
    )
    val activeVisualizerPreset: StateFlow<VisualizerPreset> = _activeVisualizerPreset.asStateFlow()

    private val _visualizerDraft = MutableStateFlow<VisualizerPreset?>(null)
    val visualizerDraft: StateFlow<VisualizerPreset?> = _visualizerDraft.asStateFlow()

    private val _effectiveVisualizerPreset = MutableStateFlow(_activeVisualizerPreset.value)
    val effectiveVisualizerPreset: StateFlow<VisualizerPreset> = _effectiveVisualizerPreset.asStateFlow()

    // Window
    private val _hideOnClose = MutableStateFlow(settings.get(SettingKey.HideOnClose, true))
    val hideOnClose: StateFlow<Boolean> = _hideOnClose.asStateFlow()

    private val _showPerformanceOverlay = MutableStateFlow(settings.get(SettingKey.ShowPerformanceOverlay, false))
    val showPerformanceOverlay: StateFlow<Boolean> = _showPerformanceOverlay.asStateFlow()

    // Proxy
    private val _isProxyEnabled = MutableStateFlow(settings.get(SettingKey.IsProxyEnabled, false))
    val isProxyEnabled: StateFlow<Boolean> = _isProxyEnabled.asStateFlow()

    private val _proxyHost = MutableStateFlow(settings.getOrNull(SettingKey.ProxyHost) ?: "")
    val proxyHost: StateFlow<String> = _proxyHost.asStateFlow()

    private val _proxyPort = MutableStateFlow(settings.getOrNull(SettingKey.ProxyPort) ?: 8080)
    val proxyPort: StateFlow<Int> = _proxyPort.asStateFlow()

    private val _proxyId = MutableStateFlow(settings.getOrNull(SettingKey.ProxyId) ?: "")
    val proxyId: StateFlow<String> = _proxyId.asStateFlow()

    private val _proxySsl = MutableStateFlow(settings.get(SettingKey.ProxySsl, false))
    val proxySsl: StateFlow<Boolean> = _proxySsl.asStateFlow()

    private val _needsUserIdMigration = MutableStateFlow(settings.get(SettingKey.NeedsUserIdMigration, true))
    val needsUserIdMigration: StateFlow<Boolean> = _needsUserIdMigration.asStateFlow()

    private val _lastSeenVersion = MutableStateFlow(settings.getOrNull(SettingKey.LastSeenVersion) ?: "")
    val lastSeenVersion: StateFlow<String> = _lastSeenVersion.asStateFlow()

    private val _lastSeenRecentReleaseId = MutableStateFlow(settings.getOrNull(SettingKey.LastSeenRecentReleaseId) ?: "")
    val lastSeenRecentReleaseId: StateFlow<String> = _lastSeenRecentReleaseId.asStateFlow()

    private val _audioOutputDevice = MutableStateFlow(settings.getOrNull(SettingKey.AudioOutputDevice))
    val audioOutputDevice: StateFlow<String?> = _audioOutputDevice.asStateFlow()

    private val _audioBufferSize = MutableStateFlow(settings.getOrNull(SettingKey.AudioBufferSize))
    val audioBufferSize: StateFlow<Int?> = _audioBufferSize.asStateFlow()

    private val _audioBufferCount = MutableStateFlow(settings.getOrNull(SettingKey.AudioBufferCount))
    val audioBufferCount: StateFlow<Int?> = _audioBufferCount.asStateFlow()

    private val _audioTargetSampleRate = MutableStateFlow(settings.getOrNull(SettingKey.AudioTargetSampleRate))
    val audioTargetSampleRate: StateFlow<Int?> = _audioTargetSampleRate.asStateFlow()

    private val _streamingQuality = MutableStateFlow(settings.get(SettingKey.StreamingQuality, 0))
    val streamingQuality: StateFlow<Int> = _streamingQuality.asStateFlow()

    fun setDarkTheme(isDark: Boolean) {
        _darkTheme.value = isDark
        settings.putBoolean("dark_theme", isDark)
    }

    fun setIconStyle(styleId: String) {
        _iconStyle.value = styleId
        settings.put(SettingKey.IconStyle, styleId)
    }

    fun setIconFilled(filled: Boolean) {
        _iconFilled.value = filled
        settings.put(SettingKey.IconFilled, filled)
    }

    fun setIconPack(pack: IconPackType) {
        _iconPack.value = pack
        settings.put(SettingKey.IconPack, pack.name)
    }

    fun setShowRemainingTime(show: Boolean) {
        _showRemainingTime.value = show
        settings.put(SettingKey.ShowRemainingTime, show)
    }

    fun setShowTitleTagsInText(show: Boolean) {
        _showTitleTagsInText.value = show
        settings.put(SettingKey.ShowTitleTagsInText, show)
    }

    fun setLanguage(lang: String?) {
        _language.value = lang
        if (lang == null) {
            settings.remove("language")
        } else {
            settings.putString("language", lang)
        }
    }

    fun setLightThemeColor(color: Color) {
        _lightThemeColor.value = color
        settings.put(SettingKey.LightThemeColor, color.toArgb())
        if (!_useSongColor.value) {
            _lightColorScheme.value =
                createColorSchemeFromSeeds(Triple(color.toArgb(), null, null), false)
        }
    }

    fun setDarkThemeColor(color: Color) {
        _darkThemeColor.value = color
        settings.put(SettingKey.DarkThemeColor, color.toArgb())
        if (!_useSongColor.value) {
            _darkColorScheme.value = createColorSchemeFromSeeds(Triple(color.toArgb(), null, null), true)
        }
    }

    fun setUseSongColor(use: Boolean) {
        _useSongColor.value = use
        settings.put(SettingKey.UseSongColor, use)
        if (!use) {
            _lightColorScheme.value = createColorSchemeFromSeeds(Triple(_lightThemeColor.value.toArgb(), null, null), false)
            _darkColorScheme.value = createColorSchemeFromSeeds(Triple(_darkThemeColor.value.toArgb(), null, null), true)
        }
    }

    fun setUsePywal(use: Boolean) {
        _usePywal.value = use
        settings.put(SettingKey.UsePywal, use)
    }

    fun setDarkColorScheme(colorScheme: ColorScheme) {
        _darkColorScheme.value = colorScheme
    }

    fun setLightColorScheme(colorScheme: ColorScheme) {
        _lightColorScheme.value = colorScheme
    }

    fun setIsServerScrobblingEnabled(enabled: Boolean) {
        _isServerScrobblingEnabled.value = enabled
        settings.put(SettingKey.IsServerScrobblingEnabled, enabled)
    }

    fun setIsListenBrainzEnabled(enabled: Boolean) {
        _isListenBrainzEnabled.value = enabled
        settings.put(SettingKey.IsListenBrainzEnabled, enabled)
    }

    fun setListenBrainzToken(token: String) {
        _listenBrainzToken.value = token
        settings.put(SettingKey.ListenBrainzToken, token)
    }

    fun setIsLastFmEnabled(enabled: Boolean) {
        _isLastFmEnabled.value = enabled
        settings.put(SettingKey.IsLastFmEnabled, enabled)
    }

    fun setLastFmApiKey(apiKey: String) {
        _lastFmApiKey.value = apiKey
        settings.put(SettingKey.LastFmApiKey, apiKey)
    }

    fun setLastFmSharedSecret(sharedSecret: String) {
        _lastFmSharedSecret.value = sharedSecret
        settings.put(SettingKey.LastFmSharedSecret, sharedSecret)
    }

    fun setLastFmSessionKey(sessionKey: String) {
        _lastFmSessionKey.value = sessionKey
        settings.put(SettingKey.LastFmSessionKey, sessionKey)
    }

    fun setLastFmUsername(username: String) {
        _lastFmUsername.value = username
        settings.put(SettingKey.LastFmUsername, username)
    }

    fun setIsDiscordRpcEnabled(enabled: Boolean) {
        _isDiscordRpcEnabled.value = enabled
        settings.put(SettingKey.IsDiscordRpcEnabled, enabled)
    }

    fun setIsQueueSyncEnabled(enabled: Boolean) {
        _isQueueSyncEnabled.value = enabled
        settings.put(SettingKey.IsQueueSyncEnabled, enabled)
        if (!enabled && _isRemoteControlEnabled.value) setIsRemoteControlEnabled(false)
    }

    fun setIsRemoteControlEnabled(enabled: Boolean) {
        _isRemoteControlEnabled.value = enabled
        settings.put(SettingKey.IsRemoteControlEnabled, enabled)
        if (enabled && !_isQueueSyncEnabled.value) setIsQueueSyncEnabled(true)
    }

    fun setRemoteControlOptInShown(shown: Boolean) {
        _remoteControlOptInShown.value = shown
        settings.put(SettingKey.RemoteControlOptInShown, shown)
    }

    fun setPodcastsEnabled(enabled: Boolean) {
        _isPodcastsEnabled.value = enabled
        settings.put(SettingKey.IsPodcastsEnabled, enabled)
    }

    fun setPodcastPlaybackSpeed(speed: Float) {
        _podcastPlaybackSpeed.value = speed
        settings.put(SettingKey.PodcastPlaybackSpeed, speed)
    }

    fun setPodcastOptInShown(shown: Boolean) {
        _podcastOptInShown.value = shown
        settings.put(SettingKey.PodcastOptInShown, shown)
    }

    fun setQueueSyncDeviceName(name: String) {
        _queueSyncDeviceName.value = name
        settings.put(SettingKey.QueueSyncDeviceName, name)
    }

    fun setIsSettingsSyncEnabled(enabled: Boolean) {
        _isSettingsSyncEnabled.value = enabled
        settings.put(SettingKey.IsSettingsSyncEnabled, enabled)
    }

    fun setIsSecretsSyncEnabled(enabled: Boolean) {
        _isSecretsSyncEnabled.value = enabled
        settings.put(SettingKey.IsSecretsSyncEnabled, enabled)
    }

    fun setSyncSetupShown(shown: Boolean) {
        _syncSetupShown.value = shown
        settings.put(SettingKey.SyncSetupShown, shown)
    }

    fun setParticleMultiplier(multiplier: Float) {
        _particleMultiplier.value = multiplier
        settings.put(SettingKey.ParticleMultiplier, multiplier)
    }

    fun selectVisualizerPreset(id: String) {
        _visualizerDraft.value = null
        setActiveVisualizerPresetId(id)
    }

    fun updateVisualizerDraft(transform: (VisualizerPreset) -> VisualizerPreset) {
        val active = _activeVisualizerPreset.value
        val updated = transform(_visualizerDraft.value ?: active).copy(id = active.id, builtIn = active.builtIn)
        _visualizerDraft.value = updated.takeIf { it != active }
        refreshVisualizerPresets()
    }

    fun resetVisualizerDraft() {
        _visualizerDraft.value = null
        refreshVisualizerPresets()
    }

    fun saveVisualizerDraft(): Boolean {
        val active = _activeVisualizerPreset.value
        if (active.builtIn) return false
        val draft = _visualizerDraft.value ?: return true
        _visualizerDraft.value = null
        setUserVisualizerPresets(_userVisualizerPresets.value.map { if (it.id == active.id) draft else it })
        return true
    }

    fun saveVisualizerDraftAs(name: String): String {
        val preset = (_visualizerDraft.value ?: _activeVisualizerPreset.value).copy(
            id = randomPlatformUUID().toString(),
            name = name,
            builtIn = false
        )
        _visualizerDraft.value = null
        setUserVisualizerPresets(_userVisualizerPresets.value + preset)
        setActiveVisualizerPresetId(preset.id)
        return preset.id
    }

    fun renameVisualizerPreset(id: String, name: String) {
        if (_userVisualizerPresets.value.none { it.id == id }) return
        _visualizerDraft.value?.let { draft ->
            if (draft.id == id) _visualizerDraft.value = draft.copy(name = name)
        }
        setUserVisualizerPresets(_userVisualizerPresets.value.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteVisualizerPreset(id: String) {
        if (_userVisualizerPresets.value.none { it.id == id }) return
        setUserVisualizerPresets(_userVisualizerPresets.value.filterNot { it.id == id })
        if (_activeVisualizerPresetId.value == id) selectVisualizerPreset(VisualizerPresets.SYNARA_ID)
    }

    fun setUserVisualizerPresets(presets: List<VisualizerPreset>) {
        val userPresets = presets
            .filterNot { preset -> preset.builtIn || VisualizerPresets.builtIns.any { it.id == preset.id } }
            .distinctBy { it.id }
        _userVisualizerPresets.value = userPresets
        _visualizerPresets.value = VisualizerPresets.builtIns + userPresets
        settings.put(SettingKey.VisualizerPresets, VisualizerPresets.encode(userPresets))
        refreshVisualizerPresets()
    }

    fun setActiveVisualizerPresetId(id: String) {
        if (_activeVisualizerPresetId.value != id) _visualizerDraft.value = null
        _activeVisualizerPresetId.value = id
        settings.put(SettingKey.VisualizerActivePreset, id)
        refreshVisualizerPresets()
    }

    private fun refreshVisualizerPresets() {
        val active = findVisualizerPreset(_visualizerPresets.value, _activeVisualizerPresetId.value)
        _activeVisualizerPreset.value = active
        val draft = _visualizerDraft.value?.takeIf { it.id == active.id && it != active }
        _visualizerDraft.value = draft
        _effectiveVisualizerPreset.value = draft ?: active
    }

    private fun findVisualizerPreset(presets: List<VisualizerPreset>, id: String): VisualizerPreset =
        presets.firstOrNull { it.id == id } ?: VisualizerPresets.builtIns.first()

    fun setHideOnClose(hide: Boolean) {
        _hideOnClose.value = hide
        settings.put(SettingKey.HideOnClose, hide)
    }

    fun setShowPerformanceOverlay(show: Boolean) {
        _showPerformanceOverlay.value = show
        settings.put(SettingKey.ShowPerformanceOverlay, show)
    }

    fun setIsProxyEnabled(enabled: Boolean) {
        _isProxyEnabled.value = enabled
        settings.put(SettingKey.IsProxyEnabled, enabled)
    }

    fun setProxyHost(host: String) {
        _proxyHost.value = host
        settings.put(SettingKey.ProxyHost, host)
    }

    fun setProxyPort(port: Int) {
        _proxyPort.value = port
        settings.put(SettingKey.ProxyPort, port)
    }

    fun setProxyId(id: String?) {
        _proxyId.value = id ?: ""
        settings.put(SettingKey.ProxyId, id)
    }

    fun setProxySsl(ssl: Boolean) {
        _proxySsl.value = ssl
        settings.put(SettingKey.ProxySsl, ssl)
    }

    fun setNeedsUserIdMigration(needs: Boolean) {
        _needsUserIdMigration.value = needs
        settings.put(SettingKey.NeedsUserIdMigration, needs)
    }

    fun setLastSeenVersion(version: String) {
        _lastSeenVersion.value = version
        settings.put(SettingKey.LastSeenVersion, version)
    }

    fun setLastSeenRecentReleaseId(releaseId: String) {
        _lastSeenRecentReleaseId.value = releaseId
        settings.put(SettingKey.LastSeenRecentReleaseId, releaseId)
    }

    fun setAudioOutputDevice(device: String?) {
        _audioOutputDevice.value = device
        settings.put(SettingKey.AudioOutputDevice, device)
    }

    fun setAudioBufferSize(size: Int?) {
        _audioBufferSize.value = size
        settings.put(SettingKey.AudioBufferSize, size)
    }

    fun setAudioBufferCount(count: Int?) {
        _audioBufferCount.value = count
        settings.put(SettingKey.AudioBufferCount, count)
    }

    fun setAudioTargetSampleRate(rate: Int?) {
        _audioTargetSampleRate.value = rate
        settings.put(SettingKey.AudioTargetSampleRate, rate)
    }

    fun setStreamingQuality(quality: Int) {
        _streamingQuality.value = quality
        settings.put(SettingKey.StreamingQuality, quality)
    }
}
