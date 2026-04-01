package dev.dertyp.synara

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.russhwolf.settings.Settings
import dev.dertyp.synara.settings.SettingKey
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

    // Visualizer
    private val _particleMultiplier = MutableStateFlow(settings.get(SettingKey.ParticleMultiplier, 2.5f))
    val particleMultiplier: StateFlow<Float> = _particleMultiplier.asStateFlow()

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

    private val _audioOutputDevice = MutableStateFlow(settings.getOrNull(SettingKey.AudioOutputDevice))
    val audioOutputDevice: StateFlow<String?> = _audioOutputDevice.asStateFlow()

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

    fun setParticleMultiplier(multiplier: Float) {
        _particleMultiplier.value = multiplier
        settings.put(SettingKey.ParticleMultiplier, multiplier)
    }

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

    fun setAudioOutputDevice(device: String?) {
        _audioOutputDevice.value = device
        settings.put(SettingKey.AudioOutputDevice, device)
    }
}
