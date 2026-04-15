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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.dertyp.synara.services.VideoFrameService
import org.koin.compose.koinInject
import kotlin.math.roundToLong

@Composable
actual fun SynaraVideoPlayer(
    url: String,
    modifier: Modifier,
    loop: Boolean,
    onLoaded: () -> Unit,
    onFrame: (Triple<Int?, Int?, Int?>) -> Unit,
    frameIndex: Int?
) {
    val videoService: VideoFrameService = koinInject()
    val videoFramesState by videoService.getFrames(url, onLoaded).collectAsState()
    
    var localCurrentIndex by remember(url) { mutableIntStateOf(0) }
    val currentIndex = frameIndex ?: localCurrentIndex

    if (videoFramesState != null) {
        val videoFrames = videoFramesState!!
        val frames = videoFrames.frames
        val fps = videoFrames.fps
        
        val currentBitmap = frames[currentIndex.coerceAtMost(frames.size - 1)]
        Canvas(modifier = modifier.fillMaxSize()) {
            val bitmapWidth = currentBitmap.width.toFloat()
            val bitmapHeight = currentBitmap.height.toFloat()
            val canvasWidth = size.width
            val canvasHeight = size.height

            val scale = maxOf(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
            val scaledWidth = bitmapWidth * scale
            val scaledHeight = bitmapHeight * scale

            val offsetX = (canvasWidth - scaledWidth) / 2
            val offsetY = (canvasHeight - scaledHeight) / 2

            drawImage(
                image = currentBitmap,
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt())
            )
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
