package dev.dertyp.synara

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.russhwolf.settings.Settings
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.theme.createColorSchemeFromSeeds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object Config : KoinComponent {
    private val settings: Settings by inject()

    private val _darkTheme = MutableStateFlow(settings.getBoolean("dark_theme", true))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

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

    fun setDarkTheme(isDark: Boolean) {
        _darkTheme.value = isDark
        settings.putBoolean("dark_theme", isDark)
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
}
