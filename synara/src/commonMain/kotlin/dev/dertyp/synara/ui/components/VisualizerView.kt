package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.player.PlayerSwitcher
import dev.dertyp.synara.settings.*
import dev.dertyp.synara.ui.components.visualizer.*
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject
import kotlin.math.*

data class VisualizerSource(
    val fftData: StateFlow<FloatArray>,
    val isPlaying: StateFlow<Boolean>,
    val sampleRate: StateFlow<Int>
)

private const val FLAME_RESET_GAP = 0.18f
private const val FLAME_HEIGHT_DP = 24f
private const val FLAME_MAX_HEIGHT_MULTIPLIER = 2f
private const val FLAME_WINDOW = 3
private const val WAVE_FILL_ALPHA_WITH_STROKE = 0.55f
private const val MIN_RADIAL_BARS = 8
internal const val COVER_CORNER_RADIUS_DP = 16f

@Composable
fun VisualizerView(
    preset: VisualizerPreset,
    colors: VisualizerColors,
    modifier: Modifier = Modifier,
    source: VisualizerSource? = null,
    minMaxHeightDuration: Long = 100L
) {
    when (preset.shape) {
        VisualizerShape.Strip -> StripVisualizer(preset, colors, source, minMaxHeightDuration, modifier)
        VisualizerShape.Radial -> BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
            val half = min(maxWidth.value, maxHeight.value) / 2f
            val glow = radialGlowDp(preset)
            val overhang = radialOverhangDp(preset)
            val maxLength = preset.radialMaxLength.coerceIn(VisualizerLimits.radialMaxLength)
            val roundness = radialRoundness(preset)
            val gap = radialGap(preset)
            val coverSize = fitRadialCoverSize(half - maxLength - overhang, COVER_CORNER_RADIUS_DP, roundness, gap)
                .coerceAtLeast(half * 0.35f)
            val reach = radialOutlineShape(coverSize, COVER_CORNER_RADIUS_DP, roundness, gap).outerReach
            val length = (half - reach - overhang).coerceIn(1f, maxLength)
            RadialVisualizer(
                preset = preset,
                colors = colors,
                source = source,
                coverSize = coverSize.dp,
                maxLength = length.dp,
                glowRadius = glow.dp,
                minMaxHeightDuration = minMaxHeightDuration,
                modifier = Modifier.size((half * 2f).dp)
            )
        }
    }
}

@Composable
fun VisualizerAroundCover(
    preset: VisualizerPreset,
    colors: VisualizerColors,
    modifier: Modifier = Modifier,
    source: VisualizerSource? = null,
    cover: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        if (preset.shape == VisualizerShape.Radial) {
            val coverSize = min(maxWidth.value, maxHeight.value)
            RadialVisualizer(
                preset = preset,
                colors = colors,
                source = source,
                coverSize = coverSize.dp,
                maxLength = preset.radialMaxLength.coerceIn(VisualizerLimits.radialMaxLength).dp,
                glowRadius = radialGlowDp(preset).dp,
                minMaxHeightDuration = 100L,
                modifier = Modifier.requiredSize((radialExtentDp(preset, coverSize) * 2f).dp)
            )
        }
        cover()
    }
}

internal fun radialExtentDp(preset: VisualizerPreset, coverSize: Float): Float {
    val reach = radialOutlineShape(coverSize, COVER_CORNER_RADIUS_DP, radialRoundness(preset), radialGap(preset)).outerReach
    return reach + preset.radialMaxLength.coerceIn(VisualizerLimits.radialMaxLength) + radialOverhangDp(preset)
}

private fun radialOverhangDp(preset: VisualizerPreset): Float =
    max(radialGlowDp(preset) + waveStrokeDp(preset) / 2f, flameReachDp(preset))

private fun flameReachDp(preset: VisualizerPreset): Float =
    if (flamesActive(preset)) FLAME_HEIGHT_DP * FLAME_MAX_HEIGHT_MULTIPLIER * flameIntensity(preset) else 0f

private fun flameIntensity(preset: VisualizerPreset): Float =
    preset.flameIntensity.coerceIn(VisualizerLimits.flameIntensity)

private fun flameThreshold(preset: VisualizerPreset): Float =
    preset.flameThreshold.coerceIn(VisualizerLimits.flameThreshold)

private fun flamesActive(preset: VisualizerPreset): Boolean =
    preset.flameEnabled && flameIntensity(preset) > 0f

private fun radialRoundness(preset: VisualizerPreset): Float =
    preset.radialRoundness.coerceIn(VisualizerLimits.radialRoundness)

private fun radialGap(preset: VisualizerPreset): Float =
    preset.radialInnerPadding.coerceIn(VisualizerLimits.radialInnerPadding)

private fun radialGlowDp(preset: VisualizerPreset): Float =
    if (preset.glowEnabled) preset.glowRadius.coerceIn(VisualizerLimits.glowRadius) else 0f

private fun waveStrokeDp(preset: VisualizerPreset): Float =
    if (preset.renderMode == VisualizerRenderMode.Wave) preset.waveStroke.coerceIn(VisualizerLimits.waveStroke) else 0f

@Composable
private fun resolveSource(source: VisualizerSource?): VisualizerSource {
    val playerSwitcher: PlayerSwitcher = koinInject()
    return remember(source, playerSwitcher) {
        source ?: VisualizerSource(playerSwitcher.fftData, playerSwitcher.isPlaying, playerSwitcher.sampleRate)
    }
}

@Composable
private fun rememberReaction(preset: VisualizerPreset, source: VisualizerSource): VisualizerReaction {
    val sampleRateState = remember(source) {
        mutableIntStateOf(source.sampleRate.value.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE)
    }
    LaunchedEffect(source) {
        source.sampleRate.collect { if (it > 0) sampleRateState.intValue = it }
    }
    val sampleRate = sampleRateState.intValue
    return remember(
        preset.reaction,
        preset.lowHz,
        preset.highHz,
        preset.frequencyScale,
        preset.sensitivity,
        preset.minDb,
        preset.maxDb,
        preset.riseSpeed,
        preset.fallSpeed,
        preset.falloff,
        preset.bassFalloff,
        preset.trebleTilt,
        sampleRate
    ) { preset.createReaction(sampleRate) }
}

private class VisualizerEngine(val barCount: Int, mirrored: Boolean, circular: Boolean, minHeightPx: Float) {
    val heights = FloatArray(barCount) { minHeightPx }
    val flames = FloatArray(barCount)
    private val holdMs = LongArray(barCount)
    private val barBands = IntArray(barCount)
    private val bandCount: Int
    private val bandHeights: FloatArray
    private var lastReaction: VisualizerReaction? = null
    var rotation = 0f
        private set

    init {
        bandCount = when {
            !mirrored -> {
                for (i in 0 until barCount) barBands[i] = i
                barCount
            }
            circular -> {
                for (i in 0 until barCount) barBands[i] = min(i, barCount - i)
                barCount / 2 + 1
            }
            else -> {
                val center = (barCount - 1) / 2f
                for (i in 0 until barCount) barBands[i] = floor(abs(i - center)).toInt()
                (barCount + 1) / 2
            }
        }
        bandHeights = FloatArray(bandCount) { minHeightPx }
    }

    fun step(
        fft: FloatArray,
        isPlaying: Boolean,
        reaction: VisualizerReaction,
        lengthPx: Float,
        minHeightPx: Float,
        deltaMs: Long,
        flameEnabled: Boolean,
        flameThreshold: Float,
        flameHoldMs: Long,
        rotationSpeed: Float
    ): Boolean {
        if (reaction !== lastReaction) {
            bandHeights.fill(0f)
            for (i in 0 until barCount) {
                val band = barBands[i]
                bandHeights[band] = max(bandHeights[band], heights[i])
            }
            lastReaction = reaction
        }

        reaction.update(fft, isPlaying, bandHeights, bandCount, lengthPx, minHeightPx, deltaMs)

        val burnAt = lengthPx * flameThreshold
        val resetAt = lengthPx * (flameThreshold - FLAME_RESET_GAP)
        var changed = false
        for (i in 0 until barCount) {
            val prev = heights[i]
            val height = bandHeights[barBands[i]]
            heights[i] = height
            if (!flameEnabled) {
                holdMs[i] = 0L
                if (flames[i] > 0f) {
                    flames[i] = 0f
                    changed = true
                }
            } else if (height >= burnAt) {
                holdMs[i] += deltaMs
                if (holdMs[i] > flameHoldMs) {
                    flames[i] = (flames[i] + deltaMs / 300f).coerceAtMost(1f)
                }
            } else if (height < resetAt) {
                holdMs[i] = 0L
                flames[i] = (flames[i] - deltaMs / 500f).coerceAtLeast(0f)
            } else {
                holdMs[i] = 0L
            }
            if (abs(height - prev) > 0.1f) changed = true
        }

        if (rotationSpeed != 0f) {
            rotation = (rotation + rotationSpeed * deltaMs / 1000f) % 360f
            if (isPlaying) changed = true
        }
        return changed
    }

    fun burning(): Boolean = flames.any { it > 0f }
}

@Composable
private fun rememberVisualizerTick(
    engine: VisualizerEngine,
    source: VisualizerSource,
    reaction: VisualizerReaction,
    lengthPx: Float,
    minHeightPx: Float,
    flameEnabled: Boolean,
    flameThreshold: Float,
    rotationSpeed: Float,
    minMaxHeightDuration: Long
): State<Long> {
    val isPlaying by source.isPlaying.collectAsState()
    val currentReaction by rememberUpdatedState(reaction)
    val currentFlameEnabled by rememberUpdatedState(flameEnabled)
    val currentFlameThreshold by rememberUpdatedState(flameThreshold)
    val currentRotationSpeed by rememberUpdatedState(rotationSpeed)
    val currentHold by rememberUpdatedState(minMaxHeightDuration)
    val tick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(engine, source, isPlaying, lengthPx, minHeightPx, flameEnabled) {
        var lastFrameTime = 0L
        while (true) {
            var changed = false
            withFrameMillis { frameTime ->
                val delta = if (lastFrameTime == 0L) 16L else frameTime - lastFrameTime
                lastFrameTime = frameTime
                changed = engine.step(
                    fft = source.fftData.value,
                    isPlaying = isPlaying,
                    reaction = currentReaction,
                    lengthPx = lengthPx,
                    minHeightPx = minHeightPx,
                    deltaMs = delta,
                    flameEnabled = currentFlameEnabled,
                    flameThreshold = currentFlameThreshold,
                    flameHoldMs = currentHold,
                    rotationSpeed = currentRotationSpeed
                )
            }
            if (isPlaying || changed || engine.burning()) {
                tick.longValue++
            } else {
                break
            }
        }
    }
    return tick
}

private class WaveBuffers(size: Int) {
    val xs = FloatArray(size)
    val ys = FloatArray(size)
    val ys2 = FloatArray(size)
    val fill = Path()
    val stroke = Path()
    val crestXs = FloatArray(size)
    val crestYs = FloatArray(size)
    val offsets = FloatArray(size)
    val blended = FloatArray(size)
    val sums = FloatArray(size)
    val flame = Path()
}

private fun heightBlend(colors: VisualizerColors, fraction: Float): Color =
    colors.highlight.copy(alpha = fraction).compositeOver(colors.base)

private fun fillColor(colors: VisualizerColors, fill: VisualizerFillMode, fraction: Float, position: Float): Color =
    if (fill == VisualizerFillMode.GradientAcross) lerp(colors.base, colors.highlight, position)
    else heightBlend(colors, fraction)

private fun glowColor(colors: VisualizerColors, fill: VisualizerFillMode, position: Float): Color =
    if (fill == VisualizerFillMode.GradientAcross) lerp(colors.base, colors.highlight, position)
    else colors.highlight

private fun Path.openCurve(xs: FloatArray, ys: FloatArray, n: Int, reverse: Boolean) {
    fun at(j: Int): Int {
        val clamped = j.coerceIn(0, n - 1)
        return if (reverse) n - 1 - clamped else clamped
    }
    for (s in 0 until n - 1) {
        val i0 = at(s - 1)
        val i1 = at(s)
        val i2 = at(s + 1)
        val i3 = at(s + 2)
        cubicTo(
            xs[i1] + (xs[i2] - xs[i0]) / 6f, ys[i1] + (ys[i2] - ys[i0]) / 6f,
            xs[i2] - (xs[i3] - xs[i1]) / 6f, ys[i2] - (ys[i3] - ys[i1]) / 6f,
            xs[i2], ys[i2]
        )
    }
}

private fun Path.closedCurve(xs: FloatArray, ys: FloatArray, n: Int) {
    for (s in 0 until n) {
        val i0 = (s - 1 + n) % n
        val i2 = (s + 1) % n
        val i3 = (s + 2) % n
        cubicTo(
            xs[s] + (xs[i2] - xs[i0]) / 6f, ys[s] + (ys[i2] - ys[i0]) / 6f,
            xs[i2] - (xs[i3] - xs[s]) / 6f, ys[i2] - (ys[i3] - ys[s]) / 6f,
            xs[i2], ys[i2]
        )
    }
}

private class StripLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val barWidth: Float,
    val spacing: Float,
    val barCount: Int,
    val anchor: VisualizerAnchor
) {
    val bottom get() = top + height
    val middle get() = top + height / 2f

    fun barX(i: Int): Float = left + i * (barWidth + spacing)

    fun position(i: Int): Float = if (barCount > 1) i / (barCount - 1f) else 0f

    fun barTop(barHeight: Float): Float = when (anchor) {
        VisualizerAnchor.Center -> middle - barHeight / 2f
        VisualizerAnchor.Bottom -> bottom - barHeight
        VisualizerAnchor.Top -> top
    }
}

@Composable
private fun StripVisualizer(
    preset: VisualizerPreset,
    colors: VisualizerColors,
    source: VisualizerSource?,
    minMaxHeightDuration: Long,
    modifier: Modifier
) {
    val resolved = resolveSource(source)
    val reaction = rememberReaction(preset, resolved)
    val wave = preset.renderMode == VisualizerRenderMode.Wave

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val glowDp = if (preset.glowEnabled) {
            preset.glowRadius.coerceIn(VisualizerLimits.glowRadius).coerceAtMost(min(widthDp, heightDp) / 4f)
        } else {
            0f
        }
        val glowRadius = glowDp.dp
        val insetPx = with(density) { glowRadius.toPx() }
        val widthPx = (with(density) { maxWidth.toPx() } - 2 * insetPx).coerceAtLeast(1f)
        val heightPx = (with(density) { maxHeight.toPx() } - 2 * insetPx).coerceAtLeast(1f)

        val targetBarWidthPx = with(density) { preset.barWidth.coerceIn(VisualizerLimits.barWidth).dp.toPx() }
        val spacingPx = with(density) { preset.barGap.coerceIn(VisualizerLimits.barGap).dp.toPx() }
        val minHeightPx = with(density) { preset.minBarHeight.coerceIn(VisualizerLimits.minBarHeight).dp.toPx() }
        val cornerRadiusPx = with(density) { preset.cornerRadius.coerceIn(VisualizerLimits.cornerRadius).dp.toPx() }
        val strokePx = with(density) { waveStrokeDp(preset).dp.toPx() }
        val flamePx = with(density) { FLAME_HEIGHT_DP.dp.toPx() }
        val opacity = preset.opacity.coerceIn(VisualizerLimits.opacity)
        val glowStrength = preset.glowStrength.coerceIn(VisualizerLimits.glowStrength)
        val fillMode = preset.fillMode
        val flameIntensity = flameIntensity(preset)

        val barCount = (widthPx / (targetBarWidthPx + spacingPx)).toInt().coerceAtLeast(if (wave) 2 else 1)
        val actualBarWidth = ((widthPx - (barCount - 1) * spacingPx) / barCount).coerceAtLeast(1f)
        val layout = StripLayout(insetPx, insetPx, widthPx, heightPx, actualBarWidth, spacingPx, barCount, preset.anchor)

        val engine = remember(barCount, preset.mirrored) {
            VisualizerEngine(barCount, preset.mirrored, circular = false, minHeightPx = minHeightPx)
        }
        val flameEnabled = flamesActive(preset)
        val tick = rememberVisualizerTick(
            engine = engine,
            source = resolved,
            reaction = reaction,
            lengthPx = heightPx,
            minHeightPx = minHeightPx,
            flameEnabled = flameEnabled,
            flameThreshold = flameThreshold(preset),
            rotationSpeed = 0f,
            minMaxHeightDuration = minMaxHeightDuration
        )

        val verticalBrush = remember(colors, preset.anchor, insetPx, heightPx) {
            when (preset.anchor) {
                VisualizerAnchor.Center -> Brush.verticalGradient(
                    0f to colors.highlight, 0.5f to colors.base, 1f to colors.highlight,
                    startY = layout.top, endY = layout.bottom
                )
                VisualizerAnchor.Bottom -> Brush.verticalGradient(
                    0f to colors.highlight, 1f to colors.base,
                    startY = layout.top, endY = layout.bottom
                )
                VisualizerAnchor.Top -> Brush.verticalGradient(
                    0f to colors.base, 1f to colors.highlight,
                    startY = layout.top, endY = layout.bottom
                )
            }
        }
        val acrossBrush = remember(colors, insetPx, widthPx) {
            Brush.horizontalGradient(
                listOf(colors.base, colors.highlight),
                startX = layout.left,
                endX = layout.left + layout.width
            )
        }
        val buffers = remember(barCount) { WaveBuffers(barCount) }
        val flamePath = remember { Path() }
        val strokeStyle = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        val glowStrokeStyle = remember(strokePx, insetPx) {
            Stroke(width = strokePx + insetPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        }

        if (glowRadius > 1.dp) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = glowRadius - 1.dp)
            ) {
                @Suppress("unused")
                val t = tick.value
                if (wave) {
                    buildStripWave(buffers, engine.heights, layout)
                    val stops = Array(barCount) { i ->
                        val fraction = (engine.heights[i] / heightPx).coerceIn(0f, 1f)
                        val position = layout.position(i)
                        position to glowColor(colors, fillMode, position).copy(alpha = fraction.pow(3f) * glowStrength)
                    }
                    val brush = Brush.horizontalGradient(*stops, startX = layout.left, endX = layout.left + layout.width)
                    if (preset.waveFill) {
                        drawPath(buffers.fill, brush, alpha = opacity, style = Fill)
                    } else if (strokePx > 0f) {
                        drawPath(buffers.stroke, brush, alpha = opacity, style = glowStrokeStyle)
                    }
                } else {
                    for (i in 0 until barCount) {
                        val barHeight = engine.heights[i]
                        val glowIntensity = (barHeight / heightPx).coerceIn(0f, 1f).pow(3f)
                        if (glowIntensity > 0.01f) {
                            drawRoundRect(
                                color = glowColor(colors, fillMode, layout.position(i)),
                                topLeft = Offset(layout.barX(i), layout.barTop(barHeight)),
                                size = Size(actualBarWidth, barHeight),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                alpha = glowIntensity * glowStrength * opacity
                            )
                        }
                    }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = tick.value
            if (wave) {
                buildStripWave(buffers, engine.heights, layout)
                val brush = when (fillMode) {
                    VisualizerFillMode.HeightBlend -> {
                        val stops = Array(barCount) { i ->
                            layout.position(i) to heightBlend(colors, (engine.heights[i] / heightPx).coerceIn(0f, 1f))
                        }
                        Brush.horizontalGradient(*stops, startX = layout.left, endX = layout.left + layout.width)
                    }
                    VisualizerFillMode.GradientAcross -> acrossBrush
                    VisualizerFillMode.Vertical -> verticalBrush
                }
                drawWave(buffers, brush, preset.waveFill, strokePx, strokeStyle, opacity)
                if (flameEnabled && engine.burning()) {
                    drawStripWaveFlames(engine, buffers, layout, colors.highlight, t, flamePx, flameIntensity, opacity)
                }
                return@Canvas
            }

            if (flameEnabled) {
                drawFlames(
                    engine = engine,
                    layout = layout,
                    flamePath = flamePath,
                    highlight = colors.highlight,
                    tick = t,
                    flameHeightPx = flamePx,
                    flameIntensity = flameIntensity,
                    opacity = opacity
                )
            }

            for (i in 0 until barCount) {
                val barHeight = engine.heights[i]
                val topLeft = Offset(layout.barX(i), layout.barTop(barHeight))
                val barSize = Size(actualBarWidth, barHeight)
                val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                if (fillMode == VisualizerFillMode.Vertical) {
                    drawRoundRect(brush = verticalBrush, topLeft = topLeft, size = barSize, cornerRadius = corner, alpha = opacity)
                } else {
                    drawRoundRect(
                        color = fillColor(colors, fillMode, (barHeight / heightPx).coerceIn(0f, 1f), layout.position(i)),
                        topLeft = topLeft,
                        size = barSize,
                        cornerRadius = corner,
                        alpha = opacity
                    )
                }
            }
        }
    }
}

private fun buildStripWave(buffers: WaveBuffers, heights: FloatArray, layout: StripLayout) {
    val n = layout.barCount
    val xs = buffers.xs
    val ys = buffers.ys
    val ys2 = buffers.ys2
    for (i in 0 until n) {
        xs[i] = layout.left + layout.width * layout.position(i)
        val h = heights[i]
        when (layout.anchor) {
            VisualizerAnchor.Center -> {
                ys[i] = layout.middle - h / 2f
                ys2[i] = layout.middle + h / 2f
            }
            VisualizerAnchor.Bottom -> ys[i] = layout.bottom - h
            VisualizerAnchor.Top -> ys[i] = layout.top + h
        }
    }

    val fill = buffers.fill
    val stroke = buffers.stroke
    fill.reset()
    stroke.reset()
    val last = n - 1
    if (layout.anchor == VisualizerAnchor.Center) {
        fill.moveTo(xs[0], ys[0])
        fill.openCurve(xs, ys, n, reverse = false)
        fill.lineTo(xs[last], ys2[last])
        fill.openCurve(xs, ys2, n, reverse = true)
        fill.close()
        stroke.moveTo(xs[0], ys[0])
        stroke.openCurve(xs, ys, n, reverse = false)
        stroke.moveTo(xs[0], ys2[0])
        stroke.openCurve(xs, ys2, n, reverse = false)
    } else {
        val base = if (layout.anchor == VisualizerAnchor.Bottom) layout.bottom else layout.top
        fill.moveTo(xs[0], base)
        fill.lineTo(xs[0], ys[0])
        fill.openCurve(xs, ys, n, reverse = false)
        fill.lineTo(xs[last], base)
        fill.close()
        stroke.moveTo(xs[0], ys[0])
        stroke.openCurve(xs, ys, n, reverse = false)
    }
}

private fun DrawScope.drawWave(
    buffers: WaveBuffers,
    brush: Brush,
    waveFill: Boolean,
    strokePx: Float,
    strokeStyle: Stroke,
    opacity: Float
) {
    val hasStroke = strokePx > 0f
    if (waveFill) {
        drawPath(
            buffers.fill,
            brush,
            alpha = opacity * if (hasStroke) WAVE_FILL_ALPHA_WITH_STROKE else 1f,
            style = Fill
        )
    }
    if (hasStroke) {
        drawPath(buffers.stroke, brush, alpha = opacity, style = strokeStyle)
    }
}

private fun waveFlameOffsets(
    buffers: WaveBuffers,
    n: Int,
    layer: Int,
    tick: Long,
    flameHeightPx: Float,
    flameIntensity: Float,
    phaseOrigin: Float,
    phaseStep: Float
): Float {
    val blended = buffers.blended
    val sums = buffers.sums
    val offsets = buffers.offsets
    val flickerSpeed = 4f + layer * 2f
    val layerHeight = flameHeightPx * flameIntensity * (1f - layer * 0.25f)
    var largest = 0f
    for (i in 0 until n) {
        val intensity = blended[i]
        if (intensity <= 0f) {
            offsets[i] = 0f
            continue
        }
        val heightMultiplier = (1f + (sums[i] - 1f).coerceAtLeast(0f) * 0.2f).coerceAtMost(FLAME_MAX_HEIGHT_MULTIPLIER)
        val flicker = sin(tick / flickerSpeed + (phaseOrigin + i * phaseStep) / 40f) * 0.2f + 0.8f
        val offset = intensity * layerHeight * heightMultiplier * flicker
        offsets[i] = offset
        largest = max(largest, offset)
    }
    return largest
}

private fun DrawScope.drawStripWaveFlames(
    engine: VisualizerEngine,
    buffers: WaveBuffers,
    layout: StripLayout,
    highlight: Color,
    tick: Long,
    flameHeightPx: Float,
    flameIntensity: Float,
    opacity: Float
) {
    val n = layout.barCount
    blendFlames(engine.flames, n, FLAME_WINDOW, wrap = false, buffers.blended, buffers.sums)
    val alphaScale = flameIntensity.coerceAtMost(1f) * opacity
    val phaseStep = if (n > 1) layout.width / (n - 1) else 0f
    val direction = if (layout.anchor == VisualizerAnchor.Top) 1f else -1f
    for (j in 0 until 3) {
        val largest = waveFlameOffsets(buffers, n, j, tick, flameHeightPx, flameIntensity, layout.left, phaseStep)
        if (largest <= 0f) continue
        val alpha = ((0.7f / (j + 1)) * alphaScale).coerceIn(0f, 1f)
        drawStripCrest(buffers, buffers.ys, n, direction, highlight, alpha)
        if (layout.anchor == VisualizerAnchor.Center) {
            drawStripCrest(buffers, buffers.ys2, n, 1f, highlight, alpha)
        }
    }
}

private fun DrawScope.drawStripCrest(
    buffers: WaveBuffers,
    ys: FloatArray,
    n: Int,
    direction: Float,
    highlight: Color,
    alpha: Float
) {
    val xs = buffers.xs
    val crest = buffers.crestYs
    val offsets = buffers.offsets
    var weight = 0f
    var weightedEdge = 0f
    for (i in 0 until n) {
        val offset = offsets[i]
        crest[i] = ys[i] + direction * offset
        if (offset > 0f) {
            weight += offset
            weightedEdge += ys[i] * offset
        }
    }
    if (weight <= 0f) return
    val edge = weightedEdge / weight
    var tip = edge
    for (i in 0 until n) {
        if (offsets[i] > 0f) tip = if (direction < 0f) min(tip, crest[i]) else max(tip, crest[i])
    }
    if (abs(tip - edge) < 1f) return

    val path = buffers.flame
    val last = n - 1
    path.reset()
    path.fillType = PathFillType.NonZero
    path.moveTo(xs[0], ys[0])
    path.openCurve(xs, ys, n, reverse = false)
    path.lineTo(xs[last], crest[last])
    path.openCurve(xs, crest, n, reverse = true)
    path.close()
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(highlight, highlight.copy(alpha = 0f)),
            startY = edge,
            endY = tip
        ),
        alpha = alpha
    )
}

private fun DrawScope.drawRadialWaveFlames(
    engine: VisualizerEngine,
    buffers: WaveBuffers,
    outline: RadialOutline,
    center: Offset,
    highlight: Color,
    tick: Long,
    flameHeightPx: Float,
    flameIntensity: Float,
    opacity: Float
) {
    val n = outline.count
    blendFlames(engine.flames, n, FLAME_WINDOW, wrap = true, buffers.blended, buffers.sums)
    val alphaScale = flameIntensity.coerceAtMost(1f) * opacity
    val xs = buffers.xs
    val ys = buffers.ys
    val crestXs = buffers.crestXs
    val crestYs = buffers.crestYs
    val offsets = buffers.offsets
    val path = buffers.flame
    for (j in 0 until 3) {
        val largest = waveFlameOffsets(buffers, n, j, tick, flameHeightPx, flameIntensity, 0f, outline.spacing)
        if (largest <= 0f) continue
        var weight = 0f
        var weightedRadius = 0f
        var tip = 0f
        for (k in 0 until n) {
            val offset = offsets[k]
            crestXs[k] = xs[k] + outline.normalXs[k] * offset
            crestYs[k] = ys[k] + outline.normalYs[k] * offset
            if (offset > 0f) {
                weight += offset
                weightedRadius += hypot(xs[k] - center.x, ys[k] - center.y) * offset
                tip = max(tip, hypot(crestXs[k] - center.x, crestYs[k] - center.y))
            }
        }
        if (weight <= 0f) continue
        val edge = weightedRadius / weight
        if (tip - edge < 1f) continue

        path.reset()
        path.fillType = PathFillType.EvenOdd
        path.moveTo(crestXs[0], crestYs[0])
        path.closedCurve(crestXs, crestYs, n)
        path.close()
        path.moveTo(xs[0], ys[0])
        path.closedCurve(xs, ys, n)
        path.close()
        drawPath(
            path = path,
            brush = Brush.radialGradient(
                0f to highlight,
                edge / tip to highlight,
                1f to highlight.copy(alpha = 0f),
                center = center,
                radius = tip
            ),
            alpha = ((0.7f / (j + 1)) * alphaScale).coerceIn(0f, 1f)
        )
    }
}

private fun DrawScope.drawFlames(
    engine: VisualizerEngine,
    layout: StripLayout,
    flamePath: Path,
    highlight: Color,
    tick: Long,
    flameHeightPx: Float,
    flameIntensity: Float,
    opacity: Float
) {
    val barWidth = layout.barWidth
    val pitch = barWidth + layout.spacing
    val drawUp = layout.anchor != VisualizerAnchor.Top
    val drawDown = layout.anchor != VisualizerAnchor.Bottom
    val alphaScale = flameIntensity.coerceAtMost(1f) * opacity

    for (i in 0 until layout.barCount) {
        if (engine.flames[i] <= 0f) continue
        val barHeight = engine.heights[i]
        val topY = layout.barTop(barHeight)
        val centerX = layout.barX(i) + barWidth / 2f
        drawFlame(
            engine = engine,
            i = i,
            wrap = false,
            flamePath = flamePath,
            highlight = highlight,
            tick = tick,
            centerX = centerX,
            phaseOrigin = centerX,
            pitch = pitch,
            barWidth = barWidth,
            flameHeightPx = flameHeightPx,
            flameIntensity = flameIntensity,
            alphaScale = alphaScale,
            upBaseY = topY,
            downBaseY = topY + barHeight,
            drawUp = drawUp,
            drawDown = drawDown
        )
    }
}

private fun DrawScope.drawFlame(
    engine: VisualizerEngine,
    i: Int,
    wrap: Boolean,
    flamePath: Path,
    highlight: Color,
    tick: Long,
    centerX: Float,
    phaseOrigin: Float,
    pitch: Float,
    barWidth: Float,
    flameHeightPx: Float,
    flameIntensity: Float,
    alphaScale: Float,
    upBaseY: Float,
    downBaseY: Float,
    drawUp: Boolean,
    drawDown: Boolean
) {
    val barCount = engine.barCount
    val intensity = engine.flames[i]
    if (intensity <= 0f) return

    var weightedSum = 0f
    var weightedSteps = 0f
    for (d in -FLAME_WINDOW..FLAME_WINDOW) {
        val k = if (wrap) ((i + d) % barCount + barCount) % barCount else i + d
        if (k !in 0 until barCount) continue
        val weight = engine.flames[k]
        weightedSum += weight
        weightedSteps += weight * d
    }

    if (weightedSum <= 0f) return

    val shift = weightedSteps / weightedSum * pitch
    val localCenterX = centerX + shift
    val phaseX = phaseOrigin + shift
    val localWidth = (barWidth + (weightedSum - intensity).coerceAtLeast(0f) * pitch) * 1.1f
    val localHeightMultiplier = (1f + (weightedSum - 1f).coerceAtLeast(0f) * 0.2f).coerceAtMost(FLAME_MAX_HEIGHT_MULTIPLIER)
    val baseFlameHeight = flameHeightPx * intensity * localHeightMultiplier * flameIntensity

    for (j in 0 until 3) {
        val flickerSpeed = 4f + j * 2f
        val flicker = sin(tick / flickerSpeed + phaseX / 40f) * 0.2f + 0.8f
        val phase = tick / (8f + j * 3f) + phaseX / 40f
        val flickerX = sin(phase) * (barWidth * 0.2f)

        val segmentHeight = baseFlameHeight * (1f - j * 0.25f) * flicker
        val segmentWidth = localWidth * (1.1f - j * 0.2f)
        val segmentAlpha = ((0.7f / (j + 1)) * (intensity / weightedSum) * flicker * alphaScale).coerceIn(0f, 1f)

        if (drawUp) {
            drawFlameLayer(flamePath, highlight, localCenterX, upBaseY, upBaseY - segmentHeight, segmentWidth, flickerX, segmentAlpha)
        }
        if (drawDown) {
            drawFlameLayer(flamePath, highlight, localCenterX, downBaseY, downBaseY + segmentHeight, segmentWidth, flickerX, segmentAlpha)
        }
    }
}

private fun DrawScope.drawFlameLayer(
    flamePath: Path,
    highlight: Color,
    centerX: Float,
    baseY: Float,
    tipY: Float,
    width: Float,
    flickerX: Float,
    alpha: Float
) {
    flamePath.reset()
    flamePath.moveTo(centerX + flickerX, tipY)
    flamePath.quadraticTo(
        centerX + width / 2 + flickerX * 0.5f, baseY,
        centerX + flickerX * 0.2f, baseY
    )
    flamePath.quadraticTo(
        centerX - width / 2 + flickerX * 0.5f, baseY,
        centerX + flickerX, tipY
    )
    drawPath(
        path = flamePath,
        brush = Brush.verticalGradient(
            colors = listOf(highlight.copy(alpha = 0f), highlight.copy(alpha = alpha)),
            startY = tipY,
            endY = baseY
        )
    )
}

@Composable
private fun RadialVisualizer(
    preset: VisualizerPreset,
    colors: VisualizerColors,
    source: VisualizerSource?,
    coverSize: Dp,
    maxLength: Dp,
    glowRadius: Dp,
    minMaxHeightDuration: Long,
    modifier: Modifier
) {
    val resolved = resolveSource(source)
    val reaction = rememberReaction(preset, resolved)
    val density = LocalDensity.current
    val wave = preset.renderMode == VisualizerRenderMode.Wave

    val coverPx = with(density) { coverSize.toPx() }
    val coverCornerPx = with(density) { COVER_CORNER_RADIUS_DP.dp.toPx() }
    val ringGapPx = with(density) { radialGap(preset).dp.toPx() }
    val roundness = radialRoundness(preset)
    val lengthPx = with(density) { maxLength.toPx() }.coerceAtLeast(1f)
    val barWidthPx = with(density) { preset.barWidth.coerceIn(VisualizerLimits.barWidth).dp.toPx() }
    val spacingPx = with(density) { preset.barGap.coerceIn(VisualizerLimits.barGap).dp.toPx() }
    val minHeightPx = with(density) { preset.minBarHeight.coerceIn(VisualizerLimits.minBarHeight).dp.toPx() }
    val cornerRadiusPx = with(density) { preset.cornerRadius.coerceIn(VisualizerLimits.cornerRadius).dp.toPx() }
    val strokePx = with(density) { waveStrokeDp(preset).dp.toPx() }
    val glowPx = with(density) { glowRadius.toPx() }
    val opacity = preset.opacity.coerceIn(VisualizerLimits.opacity)
    val glowStrength = preset.glowStrength.coerceIn(VisualizerLimits.glowStrength)
    val fillMode = preset.fillMode
    val radialAngle = preset.radialAngle.coerceIn(VisualizerLimits.radialAngle)
    val flamePx = with(density) { FLAME_HEIGHT_DP.dp.toPx() }
    val flameIntensity = flameIntensity(preset)
    val flameEnabled = flamesActive(preset)

    val shape = remember(coverPx, coverCornerPx, roundness, ringGapPx) {
        radialOutlineShape(coverPx, coverCornerPx, roundness, ringGapPx)
    }
    val barCount = (shape.perimeter / (barWidthPx + spacingPx)).toInt().coerceAtLeast(MIN_RADIAL_BARS)
    val outline = remember(shape, barCount) { RadialOutline(shape, barCount) }

    val engine = remember(barCount, preset.mirrored) {
        VisualizerEngine(barCount, preset.mirrored, circular = true, minHeightPx = minHeightPx)
    }
    val tick = rememberVisualizerTick(
        engine = engine,
        source = resolved,
        reaction = reaction,
        lengthPx = lengthPx,
        minHeightPx = minHeightPx,
        flameEnabled = flameEnabled,
        flameThreshold = flameThreshold(preset),
        rotationSpeed = preset.radialRotationSpeed.coerceIn(VisualizerLimits.radialRotationSpeed),
        minMaxHeightDuration = minMaxHeightDuration
    )

    val radialBrush = remember(colors, shape, lengthPx) {
        val inner = shape.halfSize
        val outer = shape.outerReach + lengthPx
        Brush.radialGradient(
            0f to colors.base,
            inner / outer to colors.base,
            1f to colors.highlight,
            radius = outer
        )
    }
    val barBrush = remember(colors, lengthPx) {
        Brush.verticalGradient(
            0f to colors.highlight,
            1f to colors.base,
            startY = -lengthPx,
            endY = 0f
        )
    }
    val buffers = remember(barCount) { WaveBuffers(barCount) }
    val flamePath = remember { Path() }
    val strokeStyle = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val glowStrokeStyle = remember(strokePx, glowPx) {
        Stroke(width = strokePx + glowPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    fun position(k: Int): Float = min(k, barCount - k) / (barCount / 2f)

    fun sampleOutline() {
        outline.sample((radialAngle + engine.rotation) / 360f * outline.perimeter)
    }

    Box(modifier = modifier) {
        if (glowRadius > 1.dp) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = glowRadius - 1.dp)
            ) {
                @Suppress("unused")
                val t = tick.value
                sampleOutline()
                if (wave) {
                    buildRadialWave(buffers, engine.heights, outline, center)
                    val brush = sweepBrush(barCount, outline.sweep, center) { k ->
                        val fraction = (engine.heights[k] / lengthPx).coerceIn(0f, 1f)
                        glowColor(colors, fillMode, position(k)).copy(alpha = fraction.pow(3f) * glowStrength)
                    }
                    if (preset.waveFill) {
                        drawPath(buffers.fill, brush, alpha = opacity, style = Fill)
                    } else if (strokePx > 0f) {
                        drawPath(buffers.stroke, brush, alpha = opacity, style = glowStrokeStyle)
                    }
                } else {
                    for (k in 0 until barCount) {
                        val barHeight = engine.heights[k]
                        val glowIntensity = (barHeight / lengthPx).coerceIn(0f, 1f).pow(3f)
                        if (glowIntensity <= 0.01f) continue
                        withBarTransform(outline, k, center) {
                            drawRoundRect(
                                color = glowColor(colors, fillMode, position(k)),
                                topLeft = Offset(-barWidthPx / 2f, -barHeight),
                                size = Size(barWidthPx, barHeight),
                                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                                alpha = glowIntensity * glowStrength * opacity
                            )
                        }
                    }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = tick.value
            sampleOutline()
            if (wave) {
                buildRadialWave(buffers, engine.heights, outline, center)
                val brush = if (fillMode == VisualizerFillMode.Vertical) {
                    radialBrush
                } else {
                    sweepBrush(barCount, outline.sweep, center) { k ->
                        fillColor(colors, fillMode, (engine.heights[k] / lengthPx).coerceIn(0f, 1f), position(k))
                    }
                }
                drawWave(buffers, brush, preset.waveFill, strokePx, strokeStyle, opacity)
                if (flameEnabled && engine.burning()) {
                    drawRadialWaveFlames(engine, buffers, outline, center, colors.highlight, t, flamePx, flameIntensity, opacity)
                }
                return@Canvas
            }

            for (k in 0 until barCount) {
                val barHeight = engine.heights[k]
                if (barHeight <= 0f) continue
                withBarTransform(outline, k, center) {
                    val topLeft = Offset(-barWidthPx / 2f, -barHeight)
                    val barSize = Size(barWidthPx, barHeight)
                    val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    if (fillMode == VisualizerFillMode.Vertical) {
                        drawRoundRect(brush = barBrush, topLeft = topLeft, size = barSize, cornerRadius = corner, alpha = opacity)
                    } else {
                        drawRoundRect(
                            color = fillColor(colors, fillMode, (barHeight / lengthPx).coerceIn(0f, 1f), position(k)),
                            topLeft = topLeft,
                            size = barSize,
                            cornerRadius = corner,
                            alpha = opacity
                        )
                    }
                }
            }

            if (flameEnabled) {
                val alphaScale = flameIntensity.coerceAtMost(1f) * opacity
                val pitch = outline.spacing
                for (k in 0 until barCount) {
                    if (engine.flames[k] <= 0f) continue
                    val barHeight = engine.heights[k]
                    withBarTransform(outline, k, center) {
                        drawFlame(
                            engine = engine,
                            i = k,
                            wrap = true,
                            flamePath = flamePath,
                            highlight = colors.highlight,
                            tick = t,
                            centerX = 0f,
                            phaseOrigin = k * pitch,
                            pitch = pitch,
                            barWidth = barWidthPx,
                            flameHeightPx = flamePx,
                            flameIntensity = flameIntensity,
                            alphaScale = alphaScale,
                            upBaseY = -barHeight,
                            downBaseY = 0f,
                            drawUp = true,
                            drawDown = false
                        )
                    }
                }
            }
        }
    }
}

private inline fun DrawScope.withBarTransform(outline: RadialOutline, k: Int, center: Offset, block: DrawScope.() -> Unit) {
    withTransform({
        translate(center.x + outline.xs[k], center.y + outline.ys[k])
        rotate(outline.normalDegrees[k], Offset.Zero)
    }, block)
}

private fun buildRadialWave(
    buffers: WaveBuffers,
    heights: FloatArray,
    outline: RadialOutline,
    center: Offset
) {
    val n = outline.count
    val xs = buffers.xs
    val ys = buffers.ys
    for (k in 0 until n) {
        xs[k] = center.x + outline.xs[k] + outline.normalXs[k] * heights[k]
        ys[k] = center.y + outline.ys[k] + outline.normalYs[k] * heights[k]
    }
    val half = outline.shape.halfSize
    val corner = outline.shape.corner
    val fill = buffers.fill
    val stroke = buffers.stroke
    fill.reset()
    stroke.reset()
    fill.fillType = PathFillType.EvenOdd
    fill.moveTo(xs[0], ys[0])
    fill.closedCurve(xs, ys, n)
    fill.close()
    fill.addRoundRect(
        RoundRect(
            left = center.x - half,
            top = center.y - half,
            right = center.x + half,
            bottom = center.y + half,
            cornerRadius = CornerRadius(corner, corner)
        )
    )
    stroke.moveTo(xs[0], ys[0])
    stroke.closedCurve(xs, ys, n)
    stroke.close()
}

private fun sweepBrush(
    n: Int,
    fractions: FloatArray,
    center: Offset,
    color: (Int) -> Color
): Brush {
    fun fraction(k: Int): Float = fractions[k]
    var start = 0
    var smallest = Float.MAX_VALUE
    for (k in 0 until n) {
        val f = fraction(k)
        if (f < smallest) {
            smallest = f
            start = k
        }
    }
    val lastIndex = (start - 1 + n) % n
    val lastFraction = fraction(lastIndex)
    val firstColor = color(start)
    val lastColor = color(lastIndex)
    val gapBefore = 1f - lastFraction
    val span = gapBefore + smallest
    val seam = lerp(lastColor, firstColor, if (span > 0f) gapBefore / span else 0f)
    val stops = Array(n + 2) { j ->
        when (j) {
            0 -> 0f to seam
            n + 1 -> 1f to seam
            else -> {
                val k = (start + j - 1) % n
                fraction(k) to if (k == start) firstColor else if (k == lastIndex) lastColor else color(k)
            }
        }
    }
    return Brush.sweepGradient(*stops, center = center)
}
