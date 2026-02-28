package dev.dertyp.synara.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import org.koin.compose.koinInject

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

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = song?.coverId,
            transitionSpec = {
                fadeIn(animationSpec = tween(1000)).togetherWith(fadeOut(animationSpec = tween(1000)))
            },
            label = "blurredBackgroundTransition",
            modifier = Modifier.matchParentSize()
        ) { coverId ->
            val audioModifier = if (audioReactive) {
                val smoothedAudioIntensity by animateFloatAsState(
                    targetValue = audioIntensity,
                    animationSpec = tween(200),
                    label = "audioIntensityAnimation"
                )

                Modifier.adjustColors(
                    saturation = .4f + smoothedAudioIntensity,
                    brightness = .8f + smoothedAudioIntensity * .4f
                )
            } else Modifier
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