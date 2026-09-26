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
import dev.dertyp.synara.player.StereoSpectrum
import dev.dertyp.synara.settings.*
import dev.dertyp.synara.ui.components.visualizer.*
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject
import kotlin.math.*

data class VisualizerSource(
    val fftData: StateFlow<FloatArray>,
    val stereoFftData: StateFlow<StereoSpectrum>,
    val isPlaying: StateFlow<Boolean>,
    val sampleRate: StateFlow<Int>
)

private const val FLAME_RESET_GAP = 0.18f
private const val FLAME_HEIGHT_DP = 24f
private const val FLAME_MAX_HEIGHT_MULTIPLIER = 2f
private const val FLAME_WINDOW = 3
private const val WAVE_FILL_ALPHA_WITH_STROKE = 0.55f
private const val MIN_RADIAL_BARS = 8
private const val OVERLAY_BAR_WIDTH = 0.5f
private const val OVERLAY_WAVE_ALPHA = 0.6f
internal const val COVER_CORNER_RADIUS_DP = 16f

internal class VisualizerSnapshot(
    val tick: Long,
    val rotation: Float = 0f,
    val height: (bar: Int, barCount: Int) -> Float,
    val overlayHeight: (bar: Int, barCount: Int) -> Float = height,
    val flame: (bar: Int, barCount: Int) -> Float = { _, _ -> 0f }
)

internal val LocalVisualizerSnapshot = staticCompositionLocalOf<VisualizerSnapshot?> { null }

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
    if (source != null) return source
    val playerSwitcher: PlayerSwitcher = koinInject()
    return remember(playerSwitcher) {
        VisualizerSource(playerSwitcher.fftData, playerSwitcher.stereoFftData, playerSwitcher.isPlaying, playerSwitcher.sampleRate)
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

internal const val LEFT_CHANNEL = 0
internal const val RIGHT_CHANNEL = 1
internal const val BOTH_CHANNELS = -1

internal data class ChannelBand(val channel: Int, val band: Int)

internal fun mirroredBandCount(barCount: Int, circular: Boolean): Int =
    if (circular) barCount / 2 + 1 else (barCount + 1) / 2

internal fun barChannelBand(bar: Int, barCount: Int, circular: Boolean): ChannelBand {
    if (circular) {
        val half = barCount / 2
        val channel = when {
            bar == 0 || bar == half -> BOTH_CHANNELS
            bar < half -> RIGHT_CHANNEL
            else -> LEFT_CHANNEL
        }
        return ChannelBand(channel, min(bar, barCount - bar))
    }
    val offset = bar - (barCount - 1) / 2f
    val channel = when {
        offset < 0f -> LEFT_CHANNEL
        offset > 0f -> RIGHT_CHANNEL
        else -> BOTH_CHANNELS
    }
    return ChannelBand(channel, floor(abs(offset)).toInt())
}

private class VisualizerEngine(
    val barCount: Int,
    mirrored: Boolean,
    circular: Boolean,
    stereo: Boolean,
    minHeightPx: Float
) {
    val overlay = stereo && !mirrored
    private val split = stereo && mirrored
    val heights = FloatArray(barCount) { minHeightPx }
    val overlayHeights = FloatArray(if (overlay) barCount else 0) { minHeightPx }
    val peaks = if (overlay) FloatArray(barCount) { minHeightPx } else heights
    val flames = FloatArray(barCount)
    private val holdMs = LongArray(barCount)
    private val barBands = IntArray(barCount)
    private val barChannels = IntArray(barCount)
    private val bandCount = if (mirrored) mirroredBandCount(barCount, circular) else barCount
    private val bandHeights = Array(if (stereo) 2 else 1) { FloatArray(bandCount) { minHeightPx } }
    private val spectra = Array(bandHeights.size) { FloatArray(0) }
    private var lastReaction: VisualizerReaction? = null
    var rotation = 0f
        private set

    init {
        for (i in 0 until barCount) {
            if (mirrored) {
                val mapped = barChannelBand(i, barCount, circular)
                barBands[i] = mapped.band
                barChannels[i] = if (split) mapped.channel else LEFT_CHANNEL
            } else {
                barBands[i] = i
                barChannels[i] = LEFT_CHANNEL
            }
        }
    }

    private fun bandHeight(i: Int): Float {
        val band = barBands[i]
        return when (val channel = barChannels[i]) {
            BOTH_CHANNELS -> max(bandHeights[LEFT_CHANNEL][band], bandHeights[RIGHT_CHANNEL][band])
            else -> bandHeights[channel][band]
        }
    }

    private fun reseed() {
        for (channel in bandHeights) channel.fill(0f)
        for (i in 0 until barCount) {
            val band = barBands[i]
            val channel = barChannels[i]
            for (c in bandHeights.indices) {
                if (!overlay && channel != BOTH_CHANNELS && channel != c) continue
                val seed = if (overlay && c == RIGHT_CHANNEL) overlayHeights[i] else heights[i]
                bandHeights[c][band] = max(bandHeights[c][band], seed)
            }
        }
    }

    fun step(
        fft: FloatArray,
        stereoFft: StereoSpectrum,
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
            reseed()
            lastReaction = reaction
        }

        if (spectra.size == 1) {
            spectra[0] = fft
        } else if (stereoFft.left.size > 1 && stereoFft.right.size > 1) {
            spectra[LEFT_CHANNEL] = stereoFft.left
            spectra[RIGHT_CHANNEL] = stereoFft.right
        } else {
            spectra[LEFT_CHANNEL] = fft
            spectra[RIGHT_CHANNEL] = fft
        }

        reaction.update(spectra, isPlaying, bandHeights, bandCount, lengthPx, minHeightPx, deltaMs)

        val burnAt = lengthPx * flameThreshold
        val resetAt = lengthPx * (flameThreshold - FLAME_RESET_GAP)
        var changed = false
        for (i in 0 until barCount) {
            val prev = heights[i]
            val height = bandHeight(i)
            heights[i] = height
            var peak = height
            if (overlay) {
                val prevOverlay = overlayHeights[i]
                val right = bandHeights[RIGHT_CHANNEL][barBands[i]]
                overlayHeights[i] = right
                peak = max(height, right)
                peaks[i] = peak
                if (abs(right - prevOverlay) > 0.1f) changed = true
            }
            if (!flameEnabled) {
                holdMs[i] = 0L
                if (flames[i] > 0f) {
                    flames[i] = 0f
                    changed = true
                }
            } else if (peak >= burnAt) {
                holdMs[i] += deltaMs
                if (holdMs[i] > flameHoldMs) {
                    flames[i] = (flames[i] + deltaMs / 300f).coerceAtMost(1f)
                }
            } else if (peak < resetAt) {
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

    fun seed(snapshot: VisualizerSnapshot, lengthPx: Float) {
        for (i in 0 until barCount) {
            heights[i] = snapshot.height(i, barCount) * lengthPx
            if (overlay) {
                overlayHeights[i] = snapshot.overlayHeight(i, barCount) * lengthPx
                peaks[i] = max(heights[i], overlayHeights[i])
            }
            flames[i] = snapshot.flame(i, barCount)
        }
        rotation = snapshot.rotation
    }
}

@Composable
private fun rememberSnapshotTick(snapshot: VisualizerSnapshot, engine: VisualizerEngine, lengthPx: Float): State<Long> =
    remember(snapshot, engine, lengthPx) {
        engine.seed(snapshot, lengthPx)
        mutableLongStateOf(snapshot.tick)
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
                    stereoFft = source.stereoFftData.value,
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

private class WaveBuffers(size: Int, stopCount: Int) {
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
    val fillStops = GradientBrush(stopCount)
    val overlayStops = GradientBrush(stopCount)
    val glowStops = GradientBrush(stopCount)
    val crests = Array(6) { GradientBrush(2) }
    val radialFlames = Array(3) { GradientBrush(3) }
    val flameLayer = GradientBrush(2)
}

private fun VisualizerColors.swapped(): VisualizerColors = VisualizerColors(base = highlight, highlight = base)

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

        val engine = remember(barCount, preset.mirrored, preset.stereo) {
            VisualizerEngine(barCount, preset.mirrored, circular = false, stereo = preset.stereo, minHeightPx = minHeightPx)
        }
        val flameEnabled = flamesActive(preset)
        val snapshot = LocalVisualizerSnapshot.current
        val tick = if (snapshot != null) {
            rememberSnapshotTick(snapshot, engine, heightPx)
        } else {
            rememberVisualizerTick(
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
        }

        val overlayColors = remember(colors) { colors.swapped() }
        val verticalBrush = remember(colors, preset.anchor, insetPx, heightPx) { stripVerticalBrush(colors, layout) }
        val acrossBrush = remember(colors, insetPx, widthPx) { stripAcrossBrush(colors, layout) }
        val overlayVerticalBrush = remember(overlayColors, preset.anchor, insetPx, heightPx) { stripVerticalBrush(overlayColors, layout) }
        val overlayAcrossBrush = remember(overlayColors, insetPx, widthPx) { stripAcrossBrush(overlayColors, layout) }
        val buffers = remember(barCount) { WaveBuffers(barCount, barCount) }
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
                    buildStripWave(buffers, engine.peaks, layout)
                    val stops = buffers.glowStops
                    for (i in 0 until barCount) {
                        val fraction = (engine.peaks[i] / heightPx).coerceIn(0f, 1f)
                        val position = layout.position(i)
                        stops.positions[i] = position
                        stops.colors[i] = glowColor(colors, fillMode, position).copy(alpha = fraction.pow(3f) * glowStrength).toArgb()
                    }
                    val brush = stops.linear(layout.left, 0f, layout.left + layout.width, 0f)
                    if (preset.waveFill) {
                        drawPath(buffers.fill, brush, alpha = opacity, style = Fill)
                    } else if (strokePx > 0f) {
                        drawPath(buffers.stroke, brush, alpha = opacity, style = glowStrokeStyle)
                    }
                } else {
                    for (i in 0 until barCount) {
                        val barHeight = engine.peaks[i]
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
                val brush = stripWaveBrush(fillMode, colors, engine.heights, heightPx, layout, buffers.fillStops, acrossBrush, verticalBrush)
                drawWave(buffers, brush, preset.waveFill, strokePx, strokeStyle, opacity)
                if (engine.overlay) {
                    buildStripWave(buffers, engine.overlayHeights, layout)
                    val overlayBrush = stripWaveBrush(
                        fillMode, overlayColors, engine.overlayHeights, heightPx, layout, buffers.overlayStops, overlayAcrossBrush, overlayVerticalBrush
                    )
                    drawWave(buffers, overlayBrush, preset.waveFill, strokePx, strokeStyle, opacity * OVERLAY_WAVE_ALPHA)
                }
                if (flameEnabled && engine.burning()) {
                    if (engine.overlay) buildStripWave(buffers, engine.peaks, layout)
                    drawStripWaveFlames(engine, buffers, layout, colors.highlight, t, flamePx, flameIntensity, opacity)
                }
                return@Canvas
            }

            if (flameEnabled) {
                drawFlames(
                    engine = engine,
                    layout = layout,
                    flamePath = flamePath,
                    flameGradient = buffers.flameLayer,
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

            if (engine.overlay) {
                val overlayWidth = actualBarWidth * OVERLAY_BAR_WIDTH
                val overlayInset = (actualBarWidth - overlayWidth) / 2f
                val overlayCornerPx = min(cornerRadiusPx, overlayWidth / 2f)
                val corner = CornerRadius(overlayCornerPx, overlayCornerPx)
                for (i in 0 until barCount) {
                    val barHeight = engine.overlayHeights[i]
                    val topLeft = Offset(layout.barX(i) + overlayInset, layout.barTop(barHeight))
                    val barSize = Size(overlayWidth, barHeight)
                    if (fillMode == VisualizerFillMode.Vertical) {
                        drawRoundRect(brush = overlayVerticalBrush, topLeft = topLeft, size = barSize, cornerRadius = corner, alpha = opacity)
                    } else {
                        drawRoundRect(
                            color = fillColor(overlayColors, fillMode, (barHeight / heightPx).coerceIn(0f, 1f), layout.position(i)),
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
}

private fun stripVerticalBrush(colors: VisualizerColors, layout: StripLayout): Brush = when (layout.anchor) {
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

private fun stripAcrossBrush(colors: VisualizerColors, layout: StripLayout): Brush =
    Brush.horizontalGradient(
        listOf(colors.base, colors.highlight),
        startX = layout.left,
        endX = layout.left + layout.width
    )

private fun stripWaveBrush(
    fillMode: VisualizerFillMode,
    colors: VisualizerColors,
    heights: FloatArray,
    heightPx: Float,
    layout: StripLayout,
    stops: GradientBrush,
    acrossBrush: Brush,
    verticalBrush: Brush
): Brush = when (fillMode) {
    VisualizerFillMode.HeightBlend -> {
        for (i in 0 until layout.barCount) {
            stops.positions[i] = layout.position(i)
            stops.colors[i] = heightBlend(colors, (heights[i] / heightPx).coerceIn(0f, 1f)).toArgb()
        }
        stops.linear(layout.left, 0f, layout.left + layout.width, 0f)
    }
    VisualizerFillMode.GradientAcross -> acrossBrush
    VisualizerFillMode.Vertical -> verticalBrush
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
        drawStripCrest(buffers, buffers.ys, n, direction, highlight, alpha, buffers.crests[j * 2])
        if (layout.anchor == VisualizerAnchor.Center) {
            drawStripCrest(buffers, buffers.ys2, n, 1f, highlight, alpha, buffers.crests[j * 2 + 1])
        }
    }
}

private fun DrawScope.drawStripCrest(
    buffers: WaveBuffers,
    ys: FloatArray,
    n: Int,
    direction: Float,
    highlight: Color,
    alpha: Float,
    gradient: GradientBrush
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
    gradient.colors[0] = highlight.toArgb()
    gradient.colors[1] = highlight.copy(alpha = 0f).toArgb()
    drawPath(
        path = path,
        brush = gradient.linear(0f, edge, 0f, tip, withPositions = false),
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
        val gradient = buffers.radialFlames[j]
        gradient.colors[0] = highlight.toArgb()
        gradient.colors[1] = highlight.toArgb()
        gradient.colors[2] = highlight.copy(alpha = 0f).toArgb()
        gradient.positions[0] = 0f
        gradient.positions[1] = edge / tip
        gradient.positions[2] = 1f
        drawPath(
            path = path,
            brush = gradient.radial(center.x, center.y, tip),
            alpha = ((0.7f / (j + 1)) * alphaScale).coerceIn(0f, 1f)
        )
    }
}

private fun DrawScope.drawFlames(
    engine: VisualizerEngine,
    layout: StripLayout,
    flamePath: Path,
    flameGradient: GradientBrush,
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
        val barHeight = engine.peaks[i]
        val topY = layout.barTop(barHeight)
        val centerX = layout.barX(i) + barWidth / 2f
        drawFlame(
            engine = engine,
            i = i,
            wrap = false,
            flamePath = flamePath,
            flameGradient = flameGradient,
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
    flameGradient: GradientBrush,
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
            drawFlameLayer(flamePath, flameGradient, highlight, localCenterX, upBaseY, upBaseY - segmentHeight, segmentWidth, flickerX, segmentAlpha)
        }
        if (drawDown) {
            drawFlameLayer(flamePath, flameGradient, highlight, localCenterX, downBaseY, downBaseY + segmentHeight, segmentWidth, flickerX, segmentAlpha)
        }
    }
}

private fun DrawScope.drawFlameLayer(
    flamePath: Path,
    gradient: GradientBrush,
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
    gradient.colors[0] = highlight.copy(alpha = 0f).toArgb()
    gradient.colors[1] = highlight.copy(alpha = alpha).toArgb()
    drawPath(
        path = flamePath,
        brush = gradient.linear(0f, tipY, 0f, baseY, withPositions = false)
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

    val engine = remember(barCount, preset.mirrored, preset.stereo) {
        VisualizerEngine(barCount, preset.mirrored, circular = true, stereo = preset.stereo, minHeightPx = minHeightPx)
    }
    val snapshot = LocalVisualizerSnapshot.current
    val tick = if (snapshot != null) {
        rememberSnapshotTick(snapshot, engine, lengthPx)
    } else {
        rememberVisualizerTick(
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
    }

    val overlayColors = remember(colors) { colors.swapped() }
    val radialBrush = remember(colors, shape, lengthPx) { radialFillBrush(colors, shape, lengthPx) }
    val barBrush = remember(colors, lengthPx) { radialBarBrush(colors, lengthPx) }
    val overlayRadialBrush = remember(overlayColors, shape, lengthPx) { radialFillBrush(overlayColors, shape, lengthPx) }
    val overlayBarBrush = remember(overlayColors, lengthPx) { radialBarBrush(overlayColors, lengthPx) }
    val buffers = remember(barCount) { WaveBuffers(barCount, barCount + 2) }
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
                    buildRadialWave(buffers, engine.peaks, outline, center)
                    val brush = sweepBrush(buffers.glowStops, barCount, outline.sweep, center) { k ->
                        val fraction = (engine.peaks[k] / lengthPx).coerceIn(0f, 1f)
                        glowColor(colors, fillMode, position(k)).copy(alpha = fraction.pow(3f) * glowStrength)
                    }
                    if (preset.waveFill) {
                        drawPath(buffers.fill, brush, alpha = opacity, style = Fill)
                    } else if (strokePx > 0f) {
                        drawPath(buffers.stroke, brush, alpha = opacity, style = glowStrokeStyle)
                    }
                } else {
                    for (k in 0 until barCount) {
                        val barHeight = engine.peaks[k]
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
                    sweepBrush(buffers.fillStops, barCount, outline.sweep, center) { k ->
                        fillColor(colors, fillMode, (engine.heights[k] / lengthPx).coerceIn(0f, 1f), position(k))
                    }
                }
                drawWave(buffers, brush, preset.waveFill, strokePx, strokeStyle, opacity)
                if (engine.overlay) {
                    buildRadialWave(buffers, engine.overlayHeights, outline, center)
                    val overlayBrush = if (fillMode == VisualizerFillMode.Vertical) {
                        overlayRadialBrush
                    } else {
                        sweepBrush(buffers.overlayStops, barCount, outline.sweep, center) { k ->
                            fillColor(overlayColors, fillMode, (engine.overlayHeights[k] / lengthPx).coerceIn(0f, 1f), position(k))
                        }
                    }
                    drawWave(buffers, overlayBrush, preset.waveFill, strokePx, strokeStyle, opacity * OVERLAY_WAVE_ALPHA)
                }
                if (flameEnabled && engine.burning()) {
                    if (engine.overlay) buildRadialWave(buffers, engine.peaks, outline, center)
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

            if (engine.overlay) {
                val overlayWidth = barWidthPx * OVERLAY_BAR_WIDTH
                val overlayCornerPx = min(cornerRadiusPx, overlayWidth / 2f)
                val corner = CornerRadius(overlayCornerPx, overlayCornerPx)
                for (k in 0 until barCount) {
                    val barHeight = engine.overlayHeights[k]
                    if (barHeight <= 0f) continue
                    withBarTransform(outline, k, center) {
                        val topLeft = Offset(-overlayWidth / 2f, -barHeight)
                        val barSize = Size(overlayWidth, barHeight)
                        if (fillMode == VisualizerFillMode.Vertical) {
                            drawRoundRect(brush = overlayBarBrush, topLeft = topLeft, size = barSize, cornerRadius = corner, alpha = opacity)
                        } else {
                            drawRoundRect(
                                color = fillColor(overlayColors, fillMode, (barHeight / lengthPx).coerceIn(0f, 1f), position(k)),
                                topLeft = topLeft,
                                size = barSize,
                                cornerRadius = corner,
                                alpha = opacity
                            )
                        }
                    }
                }
            }

            if (flameEnabled) {
                val alphaScale = flameIntensity.coerceAtMost(1f) * opacity
                val pitch = outline.spacing
                for (k in 0 until barCount) {
                    if (engine.flames[k] <= 0f) continue
                    val barHeight = engine.peaks[k]
                    withBarTransform(outline, k, center) {
                        drawFlame(
                            engine = engine,
                            i = k,
                            wrap = true,
                            flamePath = flamePath,
                            flameGradient = buffers.flameLayer,
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

private fun radialFillBrush(colors: VisualizerColors, shape: RadialOutlineShape, lengthPx: Float): Brush {
    val inner = shape.halfSize
    val outer = shape.outerReach + lengthPx
    return Brush.radialGradient(
        0f to colors.base,
        inner / outer to colors.base,
        1f to colors.highlight,
        radius = outer
    )
}

private fun radialBarBrush(colors: VisualizerColors, lengthPx: Float): Brush =
    Brush.verticalGradient(
        0f to colors.highlight,
        1f to colors.base,
        startY = -lengthPx,
        endY = 0f
    )

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

private inline fun sweepBrush(
    stops: GradientBrush,
    n: Int,
    fractions: FloatArray,
    center: Offset,
    color: (Int) -> Color
): Brush {
    var start = 0
    var smallest = Float.MAX_VALUE
    for (k in 0 until n) {
        val f = fractions[k]
        if (f < smallest) {
            smallest = f
            start = k
        }
    }
    val lastIndex = (start - 1 + n) % n
    val lastFraction = fractions[lastIndex]
    val firstColor = color(start)
    val lastColor = color(lastIndex)
    val gapBefore = 1f - lastFraction
    val span = gapBefore + smallest
    val seam = lerp(lastColor, firstColor, if (span > 0f) gapBefore / span else 0f).toArgb()
    val colors = stops.colors
    val positions = stops.positions
    positions[0] = 0f
    colors[0] = seam
    for (j in 1..n) {
        val k = (start + j - 1) % n
        positions[j] = fractions[k]
        colors[j] = (if (k == start) firstColor else if (k == lastIndex) lastColor else color(k)).toArgb()
    }
    positions[n + 1] = 1f
    colors[n + 1] = seam
    return stops.sweep(center.x, center.y)
}
