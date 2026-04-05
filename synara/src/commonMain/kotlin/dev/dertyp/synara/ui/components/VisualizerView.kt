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
import dev.dertyp.synara.player.PlayerModel
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

        val smoothingFactor = 0.75f
        val smoothedHeights = remember(barCount) { FloatArray(barCount) { minHeightPx } }
        val maxHeightDuration = remember(barCount) { LongArray(barCount) }
        val flameIntensities = remember(barCount) { FloatArray(barCount) }

        var tick by remember { mutableLongStateOf(0L) }

        LaunchedEffect(isPlaying, barCount, heightPx) {
            var lastFrameTime = 0L
            while (true) {
                var hasChanges = false
                withFrameMillis { frameTime ->
                    val delta = if (lastFrameTime == 0L) 0L else frameTime - lastFrameTime
                    lastFrameTime = frameTime

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

                            if (smoothedHeights[leftIdx] >= heightPx * 0.98f) {
                                maxHeightDuration[leftIdx] += delta
                                if (maxHeightDuration[leftIdx] > minMaxHeightDuration) {
                                    flameIntensities[leftIdx] = (flameIntensities[leftIdx] + delta / 1000f).coerceAtMost(1f)
                                }
                            } else if (smoothedHeights[leftIdx] < heightPx * 0.80f) {
                                maxHeightDuration[leftIdx] = 0L
                                flameIntensities[leftIdx] = (flameIntensities[leftIdx] - delta / 500f).coerceAtLeast(0f)
                            } else {
                                maxHeightDuration[leftIdx] = 0L
                            }

                            if (abs(smoothedHeights[leftIdx] - prev) > 0.1f) hasChanges = true
                        }
                        if (rightIdx in 0 until barCount && rightIdx != leftIdx) {
                            val prev = smoothedHeights[rightIdx]
                            smoothedHeights[rightIdx] = (prev * smoothingFactor) + (targetHeight * (1f - smoothingFactor))

                            if (smoothedHeights[rightIdx] >= heightPx * 0.98f) {
                                maxHeightDuration[rightIdx] += delta
                                if (maxHeightDuration[rightIdx] > minMaxHeightDuration) {
                                    flameIntensities[rightIdx] = (flameIntensities[rightIdx] + delta / 1000f).coerceAtMost(1f)
                                }
                            } else if (smoothedHeights[rightIdx] < heightPx * 0.80f) {
                                maxHeightDuration[rightIdx] = 0L
                                flameIntensities[rightIdx] = (flameIntensities[rightIdx] - delta / 500f).coerceAtLeast(0f)
                            } else {
                                maxHeightDuration[rightIdx] = 0L
                            }

                            if (abs(smoothedHeights[rightIdx] - prev) > 0.1f) hasChanges = true
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

            var startIdx = -1
            for (i in 0..barCount) {
                val isBurning = i < barCount && flameIntensities[i] > 0f
                if (isBurning) {
                    if (startIdx == -1) startIdx = i
                } else if (startIdx != -1) {
                    val start = startIdx
                    val end = i - 1
                    val groupSize = end - start + 1
                    val groupWidth = groupSize * actualBarWidth + (groupSize - 1) * spacingPx
                    val groupStartX = start * (actualBarWidth + spacingPx) + glowRadiusPx
                    val groupCenterX = groupStartX + groupWidth / 2f

                    var maxFlameIntensity = 0f
                    var maxGroupHeight = 0f
                    for (k in start..end) {
                        maxFlameIntensity = max(maxFlameIntensity, flameIntensities[k])
                        maxGroupHeight = max(maxGroupHeight, smoothedHeights[k])
                    }

                    val heightMultiplier = (1f + (groupSize - 1) * 0.2f).coerceAtMost(2f)
                    val baseFlameHeight = 24.dp.toPx() * maxFlameIntensity * heightMultiplier

                    val centerY = (heightPx / 2f) + glowRadiusPx
                    val topY = centerY - maxGroupHeight / 2f
                    val bottomY = centerY + maxGroupHeight / 2f

                    for (j in 0 until 3) {
                        val flicker = sin(tick / (4f + j)) * 0.2f + 0.8f
                        val phase = tick / (8f + j)
                        val flickerX = sin(phase) * (groupWidth * 0.1f)

                        val segmentHeight = baseFlameHeight * (1f - j * 0.25f) * flicker
                        val segmentWidth = groupWidth * (1.1f - j * 0.2f)
                        val segmentAlpha = (0.7f / (j + 1)) * maxFlameIntensity * flicker

                        val topPath = Path().apply {
                            moveTo(groupCenterX + flickerX, topY - segmentHeight)
                            quadraticTo(
                                groupCenterX + segmentWidth / 2 + flickerX * 0.5f, topY,
                                groupCenterX + flickerX * 0.2f, topY
                            )
                            quadraticTo(
                                groupCenterX - segmentWidth / 2 + flickerX * 0.5f, topY,
                                groupCenterX + flickerX, topY - segmentHeight
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
                            moveTo(groupCenterX + flickerX, bottomY + segmentHeight)
                            quadraticTo(
                                groupCenterX + segmentWidth / 2 + flickerX * 0.5f, bottomY,
                                groupCenterX + flickerX * 0.2f, bottomY
                            )
                            quadraticTo(
                                groupCenterX - segmentWidth / 2 + flickerX * 0.5f, bottomY,
                                groupCenterX + flickerX, bottomY + segmentHeight
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
                    startIdx = -1
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
