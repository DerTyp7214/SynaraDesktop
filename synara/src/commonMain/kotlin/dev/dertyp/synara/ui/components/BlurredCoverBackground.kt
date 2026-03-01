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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.SuccessResult
import coil3.toBitmap
import com.kmpalette.palette.graphics.Palette
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private val luminanceCache = LruCache<PlatformUUID, Float>(1000)

@Composable
fun rememberCoverLuminance(coverId: PlatformUUID?): Float {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val pxValue = with(density) { 250.dp.toPx() }

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
            ImageModel.withSize(context, coverId, pxValue.roundToInt()) ?: return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) {
            val calculatedLuminance = withContext(Dispatchers.Default) {
                val bitmap = result.image.toBitmap()
                val palette = Palette.from(bitmap.asComposeImageBitmap()).generate()
                val swatch = palette.dominantSwatch ?: palette.vibrantSwatch
                swatch?.let { Color(it.rgb).luminance() } ?: 0.5f
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

            val baseBrightness = 0.7f + (0.3f - coverLuminance) * 0.4f

            val audioModifier = if (audioReactive) {
                Modifier.adjustColors(
                    saturation = .4f + smoothedAudioIntensity,
                    brightness = baseBrightness + smoothedAudioIntensity * (.3f * (1f - coverLuminance))
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

        this[0, 0] *= brightness
        this[1, 1] *= brightness
        this[2, 2] *= brightness
    }

    this.colorFilter = ColorFilter.colorMatrix(matrix)
}
