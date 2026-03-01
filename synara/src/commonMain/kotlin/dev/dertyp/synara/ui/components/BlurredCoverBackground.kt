package dev.dertyp.synara.ui.components

import androidx.collection.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

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

@Composable
fun BlurredCoverBackground(
    song: UserSong?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 80.dp,
    alpha: Float = 0.5f,
    audioReactive: Boolean = false,
    playerModel: PlayerModel = koinInject(),
    content: @Composable BoxScope.() -> Unit = {}
) {
    val audioIntensity by playerModel.audioIntensity.collectAsState()
    val isPlaying by playerModel.isPlaying.collectAsState()

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = song?.coverId,
            transitionSpec = {
                fadeIn(animationSpec = tween(1000)).togetherWith(fadeOut(animationSpec = tween(1000)))
            },
            label = "blurredBackgroundTransition",
            modifier = Modifier.matchParentSize()
        ) { coverId ->
            val coverLuminance = rememberCoverLuminance(coverId)

            val smoothedAudioIntensity by animateFloatAsState(
                targetValue = if (isPlaying) audioIntensity else 0f,
                animationSpec = tween(if (isPlaying) 200 else 400),
                label = "audioIntensityAnimation"
            )

            val baseBrightness = (0.35f + (0.25f - coverLuminance) * 0.5f).coerceIn(0.1f, 0.6f)

            val audioModifier = if (audioReactive) {
                Modifier.adjustColors(
                    saturation = .4f + smoothedAudioIntensity,
                    brightness = baseBrightness + smoothedAudioIntensity * (0.3f * (1f - coverLuminance * 0.5f))
                )
            } else {
                Modifier.adjustColors(
                    saturation = 1f,
                    brightness = baseBrightness
                )
            }

            if (coverId != null) {
                val imageRequest = rememberImageRequest(coverId, size = 300.dp)
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(blurRadius)
                        .then(audioModifier),
                    contentScale = ContentScale.Crop,
                    alpha = alpha
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        content()
    }
}

private fun Modifier.adjustColors(
    saturation: Float = 1f,
    brightness: Float = 1f
): Modifier = this.graphicsLayer {
    val matrix = ColorMatrix().apply {
        setToSaturation(saturation)

        for (row in 0..2) {
            for (col in 0..4) {
                this[row, col] *= brightness
            }
        }
    }

    this.colorFilter = ColorFilter.colorMatrix(matrix)
}
