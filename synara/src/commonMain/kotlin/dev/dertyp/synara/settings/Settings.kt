package dev.dertyp.synara.settings

import com.russhwolf.settings.Settings

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

    // Scrobbling
    data object IsListenBrainzEnabled : SettingKey<Boolean>("is_listenbrainz_enabled")
    data object ListenBrainzToken : SettingKey<String>("listenbrainz_token")
    data object IsLastFmEnabled : SettingKey<Boolean>("is_lastfm_enabled")
    data object LastFmApiKey : SettingKey<String>("lastfm_api_key")
    data object LastFmSharedSecret : SettingKey<String>("lastfm_shared_secret")
    data object LastFmSessionKey : SettingKey<String>("lastfm_session_key")
    data object LastFmUsername : SettingKey<String>("lastfm_username")

    // Visualizer
    data object VisualizerBarCount : SettingKey<Int>("visualizer_bar_count")
    data object ParticleMultiplier : SettingKey<Float>("particle_multiplier")

    companion object {
        val authKeys = setOf(AuthToken.name, RefreshToken.name, TokenExpiration.name)
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
        is SettingKey.LastFmUsername -> getStringOrNull(key.name) as T?
        is SettingKey.Port, is SettingKey.LightThemeColor, is SettingKey.DarkThemeColor,
        is SettingKey.VisualizerBarCount -> getIntOrNull(key.name) as T?
        is SettingKey.TokenExpiration -> getLongOrNull(key.name) as T?
        is SettingKey.DarkTheme, is SettingKey.UseSongColor, is SettingKey.UsePywal,
        is SettingKey.IsListenBrainzEnabled, is SettingKey.IsLastFmEnabled -> getBooleanOrNull(key.name) as T?
        is SettingKey.Volume, is SettingKey.ParticleMultiplier -> getFloatOrNull(key.name) as T?
    }
}

expect class SettingsFactory() {
    fun create(): Settings
    fun getStatePath(fileName: String): String
}
