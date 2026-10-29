package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import dev.dertyp.synara.services.VideoFrameService
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.koin.compose.koinInject
import kotlin.math.roundToLong

@Composable
actual fun SynaraVideoPlayer(
    key: String,
    loader: suspend () -> ByteArray?,
    modifier: Modifier,
    loop: Boolean,
    onLoaded: () -> Unit,
    onFrame: (Triple<Int?, Int?, Int?>) -> Unit,
    frameIndex: Int?
) {
    val videoService: VideoFrameService = koinInject()
    val videoFramesState by videoService.getFrames(key, loader, onLoaded).collectAsState()

    var localCurrentIndex by remember(key) { mutableIntStateOf(0) }
    val currentIndex = frameIndex ?: localCurrentIndex

    if (videoFramesState != null) {
        val videoFrames = videoFramesState!!
        val frames = videoFrames.frames
        val fps = videoFrames.fps

        val frame = frames[currentIndex.coerceAtMost(frames.size - 1)]
        Canvas(modifier = modifier.fillMaxSize()) {
            val frameWidth = frame.width.toFloat()
            val frameHeight = frame.height.toFloat()
            val canvasWidth = size.width
            val canvasHeight = size.height

            val scale = maxOf(canvasWidth / frameWidth, canvasHeight / frameHeight)
            val scaledWidth = frameWidth * scale
            val scaledHeight = frameHeight * scale

            val offsetX = (canvasWidth - scaledWidth) / 2
            val offsetY = (canvasHeight - scaledHeight) / 2

            drawIntoCanvas {
                it.skiaCanvas.drawImageRect(
                    frame,
                    Rect.makeWH(frameWidth, frameHeight),
                    Rect.makeXYWH(
                        offsetX.toInt().toFloat(),
                        offsetY.toInt().toFloat(),
                        scaledWidth.toInt().toFloat(),
                        scaledHeight.toInt().toFloat()
                    ),
                    SamplingMode.LINEAR,
                    null,
                    true
                )
            }
        }

        if (frameIndex == null) {
            LaunchedEffect(frames, fps, loop) {
                val frameTimeNanos = (1_000_000_000L / fps).roundToLong()
                var startTime = -1L

                while (true) {
                    withFrameNanos { frameTime ->
                        if (startTime == -1L) startTime = frameTime
                        val elapsed = frameTime - startTime
                        val totalDurationNanos = frameTimeNanos * frames.size

                        if (!loop && elapsed >= totalDurationNanos) {
                            localCurrentIndex = frames.size - 1
                            return@withFrameNanos
                        }

                        val idx = ((elapsed / frameTimeNanos) % frames.size).toInt()
                        if (localCurrentIndex != idx) {
                            localCurrentIndex = idx
                            onFrame(videoFrames.seeds.getOrNull(idx) ?: Triple(null, null, null))
                        }
                    }
                }
            }
        }
    } else {
        Box(modifier = modifier)
    }
}
