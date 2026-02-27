package dev.dertyp.synara.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import coil3.toBitmap
import com.kmpalette.palette.graphics.Palette
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.Config
import dev.dertyp.synara.ui.components.ImageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val schemeCache = mutableMapOf<PlatformUUID, Triple<Int?, Int?, Int?>>()

@Composable
fun rememberCoverScheme(coverId: PlatformUUID?, isDark: Boolean): State<ColorScheme> {
    val darkColorScheme by Config.darkColorScheme.collectAsState()
    val lightColorScheme by Config.lightColorScheme.collectAsState()
    val defaultScheme = if (isDark) darkColorScheme else lightColorScheme

    val scheme = remember(isDark) {
        mutableStateOf(
            coverId?.let { id ->
                schemeCache[id]?.let { seeds ->
                    createColorSchemeFromSeeds(seeds, isDark)
                }
            } ?: defaultScheme
        )
    }

    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val pxValue = with(density) { 250.dp.toPx() }

    LaunchedEffect(coverId, isDark) {
        if (coverId == null) {
            return@LaunchedEffect
        }

        val cachedSeeds = schemeCache[coverId]
        if (cachedSeeds != null) {
            scheme.value = createColorSchemeFromSeeds(cachedSeeds, isDark)
            return@LaunchedEffect
        }

        val request = ImageModel.withSize(context, coverId, pxValue.roundToInt()) ?: return@LaunchedEffect

        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) {
            val seeds = withContext(Dispatchers.Default) {
                val bitmap = result.image.toBitmap()
                val palette = Palette.from(bitmap.asComposeImageBitmap()).generate()
                val extractedSeeds = getSeedsFromPalette(palette)
                schemeCache[coverId] = extractedSeeds
                extractedSeeds
            }

            if (seeds.first != null) {
                scheme.value = createColorSchemeFromSeeds(seeds, isDark)
            }
        }
    }

    return scheme
}

private fun getSeedsFromPalette(palette: Palette): Triple<Int?, Int?, Int?> {
    val primary = palette.vibrantSwatch ?: palette.dominantSwatch
    val secondary = palette.mutedSwatch ?: palette.dominantSwatch
    val tertiary = palette.lightVibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
    return Triple(primary?.rgb, secondary?.rgb, tertiary?.rgb)
}
