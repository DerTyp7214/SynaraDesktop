package dev.dertyp.synara.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.settings.VisualizerColorMode
import dev.dertyp.synara.settings.VisualizerPreset
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.theme.rememberCoverScheme

data class VisualizerColors(val base: Color, val highlight: Color)

internal fun sortByLuminance(a: Color, b: Color): Pair<Color, Color> =
    if (a.luminance() > b.luminance()) a to b else b to a

@Composable
fun rememberVisualizerColors(preset: VisualizerPreset, coverId: PlatformUUID?): VisualizerColors {
    val coverScheme by rememberCoverScheme(
        if (preset.colorMode == VisualizerColorMode.Cover) coverId else null,
        isAppDark()
    )
    val scheme = when (preset.colorMode) {
        VisualizerColorMode.Cover -> coverScheme
        else -> MaterialTheme.colorScheme
    }
    val primaryContainer = scheme.primaryContainer
    val tertiary = scheme.tertiary
    return remember(preset.colorMode, preset.customBaseColor, preset.customHighlightColor, primaryContainer, tertiary) {
        when (preset.colorMode) {
            VisualizerColorMode.Custom -> VisualizerColors(
                base = Color(preset.customBaseColor),
                highlight = Color(preset.customHighlightColor)
            )
            else -> {
                val (highlight, base) = sortByLuminance(primaryContainer, tertiary)
                VisualizerColors(base = base, highlight = highlight)
            }
        }
    }
}
