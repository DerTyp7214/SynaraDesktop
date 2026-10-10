package dev.dertyp.synara.ui.components

import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.luminance
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.dertyp.PlatformUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val luminanceCache = LruCache<PlatformUUID, Float>(1000)

@Composable
fun rememberCoverLuminance(coverId: PlatformUUID?): Float {
    val context = LocalPlatformContext.current

    var luminance by remember(coverId) {
        mutableFloatStateOf(coverId?.let { luminanceCache[it] } ?: 0.5f)
    }

    LaunchedEffect(coverId) {
        if (coverId == null) {
            luminance = 0.5f
            return@LaunchedEffect
        }

        val cached = luminanceCache[coverId]
        if (cached != null) {
            luminance = cached
            return@LaunchedEffect
        }

        val request =
            ImageModel.withSize(context, coverId, 64) ?: return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) {
            val calculatedLuminance = withContext(Dispatchers.Default) {
                val composeBitmap = result.image.toBitmap().asComposeImageBitmap()
                val width = composeBitmap.width
                val height = composeBitmap.height
                val size = width * height
                if (size == 0) return@withContext 0.5f

                val pixels = IntArray(size)
                composeBitmap.readPixels(pixels)

                var sum = 0.0
                for (p in pixels) {
                    sum += Color(p).luminance()
                }
                (sum / size).toFloat()
            }
            luminanceCache.put(coverId, calculatedLuminance)
            luminance = calculatedLuminance
        }
    }
    return luminance
}
