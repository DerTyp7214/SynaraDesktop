package dev.dertyp.synara.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import dev.dertyp.synara.Config
import dev.dertyp.synara.animateColorSchemeAsState
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.LocalIconFilled
import dev.dertyp.synara.ui.LocalIconPack
import dev.dertyp.synara.ui.LocalIconStyle
import org.koin.compose.koinInject

@Composable
fun SynaraTheme(content: @Composable () -> Unit) {
    val isDark = isAppDark()
    val theme = SynaraAppTheme(isDark)

    val iconPackType by Config.iconPack.collectAsState()
    val iconStyleId by Config.iconStyle.collectAsState()
    val iconFilled by Config.iconFilled.collectAsState()

    val iconPack = iconPackType.getPack()
    val iconStyle = remember(iconPack, iconStyleId) {
        iconPack.getStyle(iconStyleId)
    }

    CompositionLocalProvider(
        LocalIconPack provides iconPack,
        LocalIconStyle provides iconStyle,
        LocalIconFilled provides iconFilled,
    ) {
        MaterialTheme(
            colorScheme = theme.colorScheme,
            typography = theme.typography,
            shapes = theme.shapes,
            content = content
        )
    }
}

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
