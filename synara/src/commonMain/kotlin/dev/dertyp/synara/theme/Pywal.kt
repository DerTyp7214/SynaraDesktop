package dev.dertyp.synara.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import dev.dertyp.synara.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class PywalColors(
    val special: PywalSpecial,
    val colors: Map<String, String>
)

@Serializable
data class PywalSpecial(
    val background: String,
    val foreground: String,
    val cursor: String
)

expect object PywalLoader {
    fun isSupported(): Boolean
    fun getColorsFlow(): Flow<PywalColors?>
}

@Composable
fun rememberPywalColorScheme(isDark: Boolean): ColorScheme? {
    val usePywal by Config.usePywal.collectAsState()
    if (!usePywal) return null

    val pywalColors by remember { PywalLoader.getColorsFlow() }.collectAsState(null)
    val colors = pywalColors ?: return null

    return remember(colors, isDark) {
        val color10 = (colors.colors["color10"] ?: "#000000").toColor()
        val color12 = (colors.colors["color12"] ?: "#000000").toColor()
        val color14 = (colors.colors["color14"] ?: "#000000").toColor()

        val seeds = Triple(
            color12.filterSpecified()?.toArgb(),
            color10.filterSpecified()?.toArgb(),
            color14.filterSpecified()?.toArgb(),
        )

        createColorSchemeFromSeeds(seeds, isDark)
    }
}

private fun String.toColor(): Color {
    val hex = this.removePrefix("#")

    return when (hex.length) {
        6 -> {
            val argb = "FF$hex".toLong(16)
            Color(argb)
        }
        8 -> {
            Color(hex.toLong(16))
        }
        else -> Color.Unspecified
    }
}

private fun Color.filterSpecified() = if (isSpecified) this else null