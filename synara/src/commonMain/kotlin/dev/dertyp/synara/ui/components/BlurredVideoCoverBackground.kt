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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.core.tidalId
import dev.dertyp.data.UserSong
import dev.dertyp.services.download.IDownloadService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.services.MetadataServiceWrapper
import dev.dertyp.synara.services.VideoFrameService
import org.koin.compose.koinInject
import kotlin.math.pow
import kotlin.math.roundToLong

@Composable
fun BlurredVideoCoverBackground(
    song: UserSong?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 80.dp,
    alpha: Float = 0.5f,
    audioReactive: Boolean = false,
    playerModel: PlayerModel = koinInject(),
    metadataService: MetadataServiceWrapper = koinInject(),
    downloadService: IDownloadService = koinInject(),
    videoService: VideoFrameService = koinInject(),
    onFrame: (Triple<Int?, Int?, Int?>) -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {}
) {
    val audioIntensity by playerModel.audioIntensity.collectAsState()
    val isPlaying by playerModel.isPlaying.collectAsState()

    val currentSongId = song?.id
    val videoInfo by produceState<Pair<String?, Boolean>>(null to false, currentSongId) {
        val originalUrl = song?.originalUrl ?: return@produceState
        val downloader = try {
            downloadService.getDownloaderForUrl(originalUrl)
        } catch (_: Exception) {
            null
        } ?: return@produceState

        if (downloader.id == "tdn" || downloader.id == "tiddl") {
            val tidalId = originalUrl.tidalId()
            val images = try {
                metadataService.getTrackById(IMetadataService.MetadataType.tidal, tidalId)?.images ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val animated = images.find { it.animated }
            value = animated?.url to (animated != null)
        }
    }

    var videoLoaded by remember(videoInfo.first) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (videoLoaded) 1f else 0f,
        animationSpec = tween(1000),
        label = "videoBackgroundFade"
    )

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

            var manualSmoothedIntensity by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(audioIntensity, isPlaying) {
                var lastTime = 0L
                while (true) {
                    withFrameMillis { time ->
                        val dt = if (lastTime == 0L) 16L else time - lastTime
                        lastTime = time

                        val target = if (isPlaying) audioIntensity else 0f
                        val lerpFactor = (dt / 16.67f).coerceIn(0f, 1f)

                        val riseAlpha = 1f - 0.70f.pow(lerpFactor) 
                        val fallAlpha = 1f - 0.96f.pow(lerpFactor) 

                        val alphaValue = if (target > manualSmoothedIntensity) riseAlpha else fallAlpha
                        manualSmoothedIntensity += (target - manualSmoothedIntensity) * alphaValue
                    }
                }
            }

            val baseBrightness = (0.35f + (0.25f - coverLuminance) * 0.5f).coerceIn(0.1f, 0.6f)

            val audioModifier = if (audioReactive) {
                Modifier.adjustColors(
                    saturation = .4f + manualSmoothedIntensity.pow(2),
                    brightness = baseBrightness + manualSmoothedIntensity.pow(2) * (0.3f * (1f - coverLuminance * 0.5f))
                )
            } else {
                Modifier.adjustColors(
                    saturation = 1f,
                    brightness = baseBrightness
                )
            }

            Box(Modifier.fillMaxSize()) {
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
                }

                if (videoInfo.second && videoInfo.first != null) {
                    val url = videoInfo.first!!
                    val videoFramesState by videoService.getFrames(url) { videoLoaded = true }.collectAsState()
                    
                    if (videoFramesState != null) {
                        val videoFrames = videoFramesState!!
                        var currentIndex by remember(url) { mutableIntStateOf(0) }

                        SynaraVideoPlayer(
                            url = url,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(blurRadius)
                                .then(audioModifier)
                                .graphicsLayer { this.alpha = videoAlpha * alpha },
                            loop = true,
                            frameIndex = currentIndex,
                            onFrame = onFrame
                        )

                        LaunchedEffect(videoFrames.frames, videoFrames.fps) {
                            val frameTimeNanos = (1_000_000_000L / videoFrames.fps).roundToLong()
                            var startTime = -1L
                            while (true) {
                                withFrameNanos { frameTime ->
                                    if (startTime == -1L) startTime = frameTime
                                    val elapsed = frameTime - startTime
                                    val currentIdx = ((elapsed / frameTimeNanos) % videoFrames.frames.size).toInt()
                                    if (currentIndex != currentIdx) {
                                        currentIndex = currentIdx
                                        onFrame(videoFrames.seeds.getOrNull(currentIdx) ?: Triple(null, null, null))
                                    }
                                }
                            }
                        }

                        CompositionLocalProvider(LocalVideoFrameIndex provides currentIndex) {
                            Box(Modifier.fillMaxSize(), content = content)
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), content = content)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), content = content)
                }
            }
        }
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
