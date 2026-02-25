package dev.dertyp.synara.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.dertyp.synara.animateColorSchemeAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val LightColors = createColorSchemeFromSeeds(Triple(Color.Green.toArgb(), null, null), false)


val DarkColors = createColorSchemeFromSeeds(Triple(Color.Red.toArgb(), null, null), true)

data class AppTheme(
    val colorScheme: ColorScheme,
    val typography: Typography,
    val shapes: Shapes
)

@Composable
fun SynaraAppTheme(isDarkTheme: Boolean = isAppDark()): AppTheme {
    val scheme = SynaraColorScheme(isDarkTheme)
    return AppTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    )
}

@Composable
fun SynaraColorScheme(isDarkTheme: Boolean = isAppDark()): ColorScheme {
    val darkColorScheme by Config.darkColorScheme.collectAsState()
    val lightColorScheme by Config.lightColorScheme.collectAsState()

    val scheme by animateColorSchemeAsState(
        targetColorScheme = if (isDarkTheme) darkColorScheme else lightColorScheme
    )
    return scheme
}

object Config {
    private val _darkTheme = MutableStateFlow(true)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _darkColorScheme = MutableStateFlow(DarkColors)
    val darkColorScheme: StateFlow<ColorScheme> = _darkColorScheme.asStateFlow()

    private val _lightColorScheme = MutableStateFlow(LightColors)
    val lightColorScheme: StateFlow<ColorScheme> = _lightColorScheme

    fun setDarkTheme(isDark: Boolean) {
        _darkTheme.value = isDark
    }

    fun setDarkColorScheme(colorScheme: ColorScheme) {
        _darkColorScheme.value = colorScheme
    }

    fun setLightColorScheme(colorScheme: ColorScheme) {
        _lightColorScheme.value = colorScheme
    }
}

@Composable
fun isAppDark(): Boolean {
    val isDark by Config.darkTheme.collectAsState()
    return isDark
}