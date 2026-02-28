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
import dev.dertyp.synara.Config
import dev.dertyp.synara.animateColorSchemeAsState
import dev.dertyp.synara.player.PlayerModel
import org.koin.compose.koinInject

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
    val useSongColor by Config.useSongColor.collectAsState()
    val usePywal by Config.usePywal.collectAsState()
    
    val playerModel: PlayerModel = koinInject()
    val currentSong by playerModel.currentSong.collectAsState()
    
    val coverScheme by rememberCoverScheme(
        coverId = if (useSongColor) currentSong?.coverId else null,
        isDark = isDarkTheme
    )

    val pywalScheme = rememberPywalColorScheme(isDark = isDarkTheme)

    val targetColorScheme = when {
        usePywal && pywalScheme != null -> pywalScheme
        useSongColor && currentSong != null -> coverScheme
        else -> if (isDarkTheme) darkColorScheme else lightColorScheme
    }

    val scheme by animateColorSchemeAsState(
        targetColorScheme = targetColorScheme
    )
    return scheme
}

@Composable
fun isAppDark(): Boolean {
    val isDark by Config.darkTheme.collectAsState()
    return isDark
}
