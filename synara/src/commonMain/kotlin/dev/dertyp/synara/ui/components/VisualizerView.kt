package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.player.PlayerModel
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

@Composable
fun VisualizerView(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    playerModel: PlayerModel = koinInject()
) {
    val isPlaying by playerModel.isPlaying.collectAsState()

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        
        val targetBarWidthPx = with(density) { 5.dp.toPx() }
        val spacingPx = with(density) { 1.dp.toPx() }
        val minHeightPx = with(density) { 3.dp.toPx() }
        val cornerRadiusPx = with(density) { 2.dp.toPx() }

        val barCount = (widthPx / (targetBarWidthPx + spacingPx)).toInt().coerceAtLeast(1)
        val actualBarWidth = (widthPx - (barCount - 1) * spacingPx) / barCount
        
        val smoothingFactor = 0.75f
        val smoothedHeights = remember(barCount) { FloatArray(barCount) { minHeightPx } }

        var tick by remember { mutableLongStateOf(0L) }

        LaunchedEffect(isPlaying, barCount, heightPx) {
            while (true) {
                var hasChanges = false
                withFrameMillis {
                    val currentFft = playerModel.fftData.value
                    val centerIndex = (barCount - 1) / 2f
                    val halfCount = (barCount + 1) / 2
                    
                    val fftSize = currentFft.size
                    val maxFftBins = fftSize / 2
                    val binSize = if (halfCount > 0) maxFftBins.toFloat() / halfCount else 0f

                    val minDb = -60f
                    val maxDb = -20f

                    for (i in 0 until halfCount) {
                        var maxMagnitude = 0f
                        if (isPlaying && currentFft.isNotEmpty() && binSize > 0) {
                            val startBin = (i * binSize).toInt()
                            val endBin = ((i + 1) * binSize).toInt().coerceAtMost(maxFftBins - 1)
                            
                            for (j in startBin..endBin) {
                                maxMagnitude = maxOf(maxMagnitude, currentFft[j])
                            }
                        }

                        val targetHeight = if (isPlaying) {
                            val db = if (maxMagnitude > 0.00003f) 20f * log10(maxMagnitude) else minDb
                            val targetNormalized = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                            (targetNormalized * heightPx).coerceAtLeast(minHeightPx)
                        } else {
                            minHeightPx
                        }

                        val leftIdx = floor(centerIndex - i).toInt()
                        val rightIdx = ceil(centerIndex + i).toInt()
                        
                        if (leftIdx in 0 until barCount) {
                            val prev = smoothedHeights[leftIdx]
                            smoothedHeights[leftIdx] = (prev * smoothingFactor) + (targetHeight * (1f - smoothingFactor))
                            if (abs(smoothedHeights[leftIdx] - prev) > 0.1f) hasChanges = true
                        }
                        if (rightIdx in 0 until barCount && rightIdx != leftIdx) {
                            val prev = smoothedHeights[rightIdx]
                            smoothedHeights[rightIdx] = (prev * smoothingFactor) + (targetHeight * (1f - smoothingFactor))
                            if (abs(smoothedHeights[rightIdx] - prev) > 0.1f) hasChanges = true
                        }
                    }
                }
                
                if (isPlaying || hasChanges) {
                    tick++
                } else {
                    break
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("unused")
            val t = tick

            for (i in 0 until barCount) {
                val smoothedHeight = smoothedHeights[i]
                val x = i * (actualBarWidth + spacingPx)

                val centerY = heightPx / 2f
                val y = centerY - (smoothedHeight / 2f)

                val barColor = highlightColor.copy(alpha = (smoothedHeight / heightPx).coerceIn(0f, 1f))
                    .compositeOver(color)

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(actualBarWidth, smoothedHeight),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            }
        }
    }
}
