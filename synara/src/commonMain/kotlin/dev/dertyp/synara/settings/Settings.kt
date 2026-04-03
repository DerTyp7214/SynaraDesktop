package dev.dertyp.synara.settings

import com.russhwolf.settings.Settings
import dev.dertyp.synara.services.LocalStorageService

sealed class SettingKey<T>(val name: String) {
    data object Host : SettingKey<String>("host")
    data object Port : SettingKey<Int>("port")
    data object AuthToken : SettingKey<String>("auth_token")
    data object RefreshToken : SettingKey<String>("refresh_token")
    data object TokenExpiration : SettingKey<Long>("token_expiration")
    data object DarkTheme : SettingKey<Boolean>("dark_theme")
    data object Volume : SettingKey<Float>("volume")
    data object LightThemeColor : SettingKey<Int>("light_theme_color")
    data object DarkThemeColor : SettingKey<Int>("dark_theme_color")
    data object UseSongColor : SettingKey<Boolean>("use_song_color")
    data object UsePywal : SettingKey<Boolean>("use_pywal")
    data object AudioOutputDevice : SettingKey<String>("audio_output_device")

    // Proxy
    data object IsProxyEnabled : SettingKey<Boolean>("is_proxy_enabled")
    data object ProxyHost : SettingKey<String>("proxy_host")
    data object ProxyPort : SettingKey<Int>("proxy_port")
    data object ProxyId : SettingKey<String>("proxy_id")
    data object ProxySsl : SettingKey<Boolean>("proxy_ssl")

    // Scrobbling
    data object IsListenBrainzEnabled : SettingKey<Boolean>("is_listenbrainz_enabled")
    data object ListenBrainzToken : SettingKey<String>("listenbrainz_token")
    data object IsLastFmEnabled : SettingKey<Boolean>("is_lastfm_enabled")
    data object LastFmApiKey : SettingKey<String>("lastfm_api_key")
    data object LastFmSharedSecret : SettingKey<String>("lastfm_shared_secret")
    data object LastFmSessionKey : SettingKey<String>("lastfm_session_key")
    data object LastFmUsername : SettingKey<String>("lastfm_username")
    data object IsDiscordRpcEnabled : SettingKey<Boolean>("is_discord_rpc_enabled")

    // Visualizer
    data object ParticleMultiplier : SettingKey<Float>("particle_multiplier")

    // UI
    data object IconStyle : SettingKey<String>("icon_style")
    data object IconFilled : SettingKey<Boolean>("icon_filled")
    data object IconPack : SettingKey<String>("icon_pack")

    // Database
    data object NeedsUserIdMigration : SettingKey<Boolean>("needs_user_id_migration")

    // Window
    data object HideOnClose : SettingKey<Boolean>("hide_on_close")

    // Downloads
    data object DownloadFavorites : SettingKey<Boolean>("download_favorites")

    // Performance
    data object ShowPerformanceOverlay : SettingKey<Boolean>("show_performance_overlay")

    // Updates
    data object LastSeenVersion : SettingKey<String>("last_seen_version")
    data object LastSeenRecentReleaseId : SettingKey<String>("last_seen_recent_release_id")

    companion object {
        val authKeys = setOf(
            AuthToken.name,
            RefreshToken.name,
            TokenExpiration.name,
            ListenBrainzToken.name,
            LastFmApiKey.name,
            LastFmSharedSecret.name,
            LastFmSessionKey.name
        )
    }
}

fun <T : Any> Settings.put(key: SettingKey<T>, value: T?) {
    if (value == null) {
        remove(key.name)
        return
    }
    when (value) {
        is String -> putString(key.name, value)
        is Int -> putInt(key.name, value)
        is Long -> putLong(key.name, value)
        is Float -> putFloat(key.name, value)
        is Double -> putDouble(key.name, value)
        is Boolean -> putBoolean(key.name, value)
        else -> throw IllegalArgumentException("Unsupported type")
    }
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> Settings.get(key: SettingKey<T>, defaultValue: T): T {
    return when (defaultValue) {
        is String -> getString(key.name, defaultValue) as T
        is Int -> getInt(key.name, defaultValue) as T
        is Long -> getLong(key.name, defaultValue) as T
        is Float -> getFloat(key.name, defaultValue) as T
        is Double -> getDouble(key.name, defaultValue) as T
        is Boolean -> getBoolean(key.name, defaultValue) as T
        else -> throw IllegalArgumentException("Unsupported type")
    }
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> Settings.getOrNull(key: SettingKey<T>): T? {
    return when (key) {
        is SettingKey.Host, is SettingKey.AuthToken, is SettingKey.RefreshToken,
        is SettingKey.ListenBrainzToken, is SettingKey.LastFmApiKey,
        is SettingKey.LastFmSharedSecret, is SettingKey.LastFmSessionKey,
        is SettingKey.LastFmUsername, is SettingKey.ProxyHost, is SettingKey.ProxyId,
        is SettingKey.IconStyle, is SettingKey.IconPack, is SettingKey.LastSeenVersion,
        is SettingKey.LastSeenRecentReleaseId,
        is SettingKey.AudioOutputDevice -> getStringOrNull(key.name) as T?

        is SettingKey.Port, is SettingKey.LightThemeColor, is SettingKey.DarkThemeColor,
        is SettingKey.ProxyPort -> getIntOrNull(
            key.name
        ) as T?

        is SettingKey.TokenExpiration -> getLongOrNull(key.name) as T?
        is SettingKey.DarkTheme, is SettingKey.UseSongColor, is SettingKey.UsePywal,
        is SettingKey.IsListenBrainzEnabled, is SettingKey.IsLastFmEnabled,
        is SettingKey.IsDiscordRpcEnabled, is SettingKey.HideOnClose,
        is SettingKey.IsProxyEnabled, is SettingKey.ProxySsl,
        is SettingKey.NeedsUserIdMigration, is SettingKey.IconFilled,
        is SettingKey.DownloadFavorites,
        is SettingKey.ShowPerformanceOverlay -> getBooleanOrNull(key.name) as T?

        is SettingKey.Volume, is SettingKey.ParticleMultiplier -> getFloatOrNull(key.name) as T?
    }
}

expect class SettingsFactory(storageService: LocalStorageService) {
    fun create(): Settings
    fun getStatePath(fileName: String): String
}
