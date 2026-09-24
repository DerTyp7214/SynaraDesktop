package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.components.visualizer.VisualizerReaction
import dev.dertyp.synara.ui.components.visualizer.createReaction
import org.koin.compose.koinInject
import kotlin.math.*

@Composable
fun VisualizerView(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    minMaxHeightDuration: Long = 100L,
    playerModel: PlayerModel = koinInject()
) {
    val isPlaying by playerModel.isPlaying.collectAsState()
    val visualizerStyle by Config.visualizerStyle.collectAsState()
    val reaction by rememberUpdatedState(remember(visualizerStyle) { visualizerStyle.createReaction() })

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val glowRadius = 9.dp
        val glowRadiusPx = with(density) { glowRadius.toPx() }

        val widthPx = (with(density) { maxWidth.toPx() } - 2 * glowRadiusPx).coerceAtLeast(1f)
        val heightPx = (with(density) { maxHeight.toPx() } - 2 * glowRadiusPx).coerceAtLeast(1f)

        val targetBarWidthPx = with(density) { 5.dp.toPx() }
        val spacingPx = with(density) { 1.dp.toPx() }
        val minHeightPx = with(density) { 3.dp.toPx() }
        val cornerRadiusPx = with(density) { 2.dp.toPx() }

        val barCount = (widthPx / (targetBarWidthPx + spacingPx)).toInt().coerceAtLeast(1)
        val actualBarWidth = (widthPx - (barCount - 1) * spacingPx) / barCount

        val smoothedHeights = remember(barCount) { FloatArray(barCount) { minHeightPx } }
        val maxHeightDuration = remember(barCount) { LongArray(barCount) }
        val flameIntensities = remember(barCount) { FloatArray(barCount) }
        val bandHeights = remember(barCount) { FloatArray(barCount) { minHeightPx } }

        var tick by remember { mutableLongStateOf(0L) }

        LaunchedEffect(isPlaying, barCount, heightPx) {
            var lastFrameTime = 0L
            var lastReaction: VisualizerReaction? = null
            while (true) {
                var hasChanges = false
                withFrameMillis { frameTime ->
                    val delta = if (lastFrameTime == 0L) 16L else frameTime - lastFrameTime
                    lastFrameTime = frameTime

                    val centerIndex = (barCount - 1) / 2f
                    val halfCount = (barCount + 1) / 2
                    val currentReaction = reaction
                    val mirrored = currentReaction.mirrored
                    val bandCount = if (mirrored) halfCount else barCount

                    fun leftIndex(band: Int) = floor(centerIndex - band).toInt()
                    fun rightIndex(band: Int) = ceil(centerIndex + band).toInt()

                    if (currentReaction !== lastReaction) {
                        for (i in 0 until bandCount) {
                            bandHeights[i] = if (mirrored) {
                                maxOf(smoothedHeights[leftIndex(i)], smoothedHeights[rightIndex(i)])
                            } else {
                                smoothedHeights[i]
                            }
                        }
                        lastReaction = currentReaction
                    }

                    currentReaction.update(
                        playerModel.fftData.value,
                        isPlaying,
                        bandHeights,
                        bandCount,
                        heightPx,
                        minHeightPx,
                        delta
                    )

                    fun updateBar(idx: Int, targetHeight: Float) {
                        if (idx in 0 until barCount) {
                            val prev = smoothedHeights[idx]
                            smoothedHeights[idx] = targetHeight

                            if (smoothedHeights[idx] >= heightPx * 0.98f) {
                                maxHeightDuration[idx] += delta
                                if (maxHeightDuration[idx] > minMaxHeightDuration) {
                                    flameIntensities[idx] = (flameIntensities[idx] + delta / 300f).coerceAtMost(1f)
                                }
                            } else if (smoothedHeights[idx] < heightPx * 0.80f) {
                                maxHeightDuration[idx] = 0L
                                flameIntensities[idx] = (flameIntensities[idx] - delta / 500f).coerceAtLeast(0f)
                            } else {
                                maxHeightDuration[idx] = 0L
                            }

                            if (abs(smoothedHeights[idx] - prev) > 0.1f) hasChanges = true
                        }
                    }

                    for (i in 0 until bandCount) {
                        val targetHeight = bandHeights[i]
                        if (mirrored) {
                            val leftIdx = leftIndex(i)
                            val rightIdx = rightIndex(i)
                            updateBar(leftIdx, targetHeight)
                            if (rightIdx != leftIdx) {
                                updateBar(rightIdx, targetHeight)
                            }
                        } else {
                            updateBar(i, targetHeight)
                        }
                    }
                }

                val isAnyBarBurning = flameIntensities.any { it > 0f }
                if (isPlaying || hasChanges || isAnyBarBurning) {
                    tick++
                } else {
                    break
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = glowRadius - 1.dp)
        ) {
            @Suppress("unused")
            val t = tick

            for (i in 0 until barCount) {
                val smoothedHeight = smoothedHeights[i]
                val x = i * (actualBarWidth + spacingPx) + glowRadiusPx

                val centerY = (heightPx / 2f) + glowRadiusPx
                val y = centerY - (smoothedHeight / 2f)

                val heightFactor = (smoothedHeight / heightPx).coerceIn(0f, 1f)
                val glowIntensity = heightFactor.pow(3f)

                if (glowIntensity > 0.01f) {
                    drawRoundRect(
                        color = highlightColor.copy(alpha = glowIntensity * 0.7f),
                        topLeft = Offset(x, y),
                        size = Size(actualBarWidth, smoothedHeight),
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("unused")
            val t = tick

            for (i in 0 until barCount) {
                val intensity = flameIntensities[i]
                if (intensity <= 0f) continue

                val smoothedHeight = smoothedHeights[i]
                val centerY = (heightPx / 2f) + glowRadiusPx
                val topY = centerY - (smoothedHeight / 2f)
                val bottomY = centerY + (smoothedHeight / 2f)

                var weightedSum = 0f
                var weightedCenterX = 0f
                val window = 3
                for (k in (i - window)..(i + window)) {
                    if (k in 0 until barCount) {
                        val weight = flameIntensities[k]
                        weightedSum += weight
                        val barCenterX = k * (actualBarWidth + spacingPx) + glowRadiusPx + actualBarWidth / 2f
                        weightedCenterX += weight * barCenterX
                    }
                }

                if (weightedSum <= 0f) continue

                val localCenterX = weightedCenterX / weightedSum
                val localWidth = (actualBarWidth + (weightedSum - intensity).coerceAtLeast(0f) * (actualBarWidth + spacingPx)) * 1.1f
                val localHeightMultiplier = (1f + (weightedSum - 1f).coerceAtLeast(0f) * 0.2f).coerceAtMost(2f)
                val baseFlameHeight = 24.dp.toPx() * intensity * localHeightMultiplier

                for (j in 0 until 3) {
                    val flickerSpeed = 4f + j * 2f
                    val flicker = sin(tick / flickerSpeed + localCenterX / 40f) * 0.2f + 0.8f
                    val phase = tick / (8f + j * 3f) + localCenterX / 40f
                    val flickerX = sin(phase) * (actualBarWidth * 0.2f)

                    val segmentHeight = baseFlameHeight * (1f - j * 0.25f) * flicker
                    val segmentWidth = localWidth * (1.1f - j * 0.2f)
                    
                    val segmentAlpha = (0.7f / (j + 1)) * (intensity / weightedSum) * flicker

                    val topPath = Path().apply {
                        moveTo(localCenterX + flickerX, topY - segmentHeight)
                        quadraticTo(
                            localCenterX + segmentWidth / 2 + flickerX * 0.5f, topY,
                            localCenterX + flickerX * 0.2f, topY
                        )
                        quadraticTo(
                            localCenterX - segmentWidth / 2 + flickerX * 0.5f, topY,
                            localCenterX + flickerX, topY - segmentHeight
                        )
                    }
                    drawPath(
                        path = topPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(highlightColor.copy(alpha = 0f), highlightColor.copy(alpha = segmentAlpha)),
                            startY = topY - segmentHeight,
                            endY = topY
                        )
                    )

                    val bottomPath = Path().apply {
                        moveTo(localCenterX + flickerX, bottomY + segmentHeight)
                        quadraticTo(
                            localCenterX + segmentWidth / 2 + flickerX * 0.5f, bottomY,
                            localCenterX + flickerX * 0.2f, bottomY
                        )
                        quadraticTo(
                            localCenterX - segmentWidth / 2 + flickerX * 0.5f, bottomY,
                            localCenterX + flickerX, bottomY + segmentHeight
                        )
                    }
                    drawPath(
                        path = bottomPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(highlightColor.copy(alpha = segmentAlpha), highlightColor.copy(alpha = 0f)),
                            startY = bottomY,
                            endY = bottomY + segmentHeight
                        )
                    )
                }
            }

            for (i in 0 until barCount) {
                val smoothedHeight = smoothedHeights[i]
                val x = i * (actualBarWidth + spacingPx) + glowRadiusPx

                val centerY = (heightPx / 2f) + glowRadiusPx
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
