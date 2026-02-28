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
        is SettingKey.Host, is SettingKey.AuthToken, is SettingKey.RefreshToken -> getStringOrNull(key.name) as T?
        is SettingKey.Port, is SettingKey.LightThemeColor, is SettingKey.DarkThemeColor -> getIntOrNull(key.name) as T?
        is SettingKey.TokenExpiration -> getLongOrNull(key.name) as T?
        is SettingKey.DarkTheme, is SettingKey.UseSongColor, is SettingKey.UsePywal -> getBooleanOrNull(key.name) as T?
        is SettingKey.Volume -> getFloatOrNull(key.name) as T?
    }
}

expect class SettingsFactory() {
    fun create(): Settings
    fun getStatePath(fileName: String): String
}
