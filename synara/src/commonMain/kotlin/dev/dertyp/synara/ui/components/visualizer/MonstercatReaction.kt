package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.settings.FrequencyScale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

class MonstercatReaction(
    private val falloff: Float = 2f,
    private val bassFalloff: Float = 1.5f,
    private val bassSpread: Float = 0.25f,
    private val fallDurationMs: Float = FALL_DURATION_MS,
    private val riseNoiseReduction: Float = RISE_NOISE_REDUCTION,
    private val trebleTilt: Float = 0.5f,
    private val tiltCornerHz: Float = TILT_CORNER_HZ,
    private val headroom: Float = 0.92f,
    private val sampleRate: Float = SAMPLE_RATE,
    private val lowHz: Float = LOW_HZ,
    private val highHz: Float = HIGH_HZ,
    private val scale: FrequencyScale = FrequencyScale.Log,
    private val autoGain: Boolean = true,
    minDb: Float = -60f,
    maxDb: Float = -20f
) : VisualizerReaction {
    private val floorMagnitude = 10f.pow(minDb / 20f)
    private val gainRange = (10f.pow(maxDb / 20f) - floorMagnitude).coerceAtLeast(1e-6f)

    var sensitivity = 1f
        private set

    private var warmingUp = true
    private var velocities = emptyArray<FloatArray>()
    private var targets = emptyArray<FloatArray>()
    private var levels = FloatArray(0)
    private var edges = FloatArray(0)
    private var gains = FloatArray(0)
    private var falloffs = FloatArray(0)
    private var edgesKey = -1L

    override fun update(
        spectra: Array<FloatArray>,
        isPlaying: Boolean,
        heights: Array<FloatArray>,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    ) {
        val channels = channelCount(spectra, heights)
        if (channels <= 0) return
        val count = sharedBandCount(heights, channels, bandCount)
        if (count <= 0) return
        if (velocities.size != channels || velocities[0].size != count) {
            velocities = Array(channels) { FloatArray(count) }
            targets = Array(channels) { FloatArray(count) }
        }
        if (levels.size != count) levels = FloatArray(count)

        val binCount = sharedBinCount(spectra, channels)
        if (isPlaying && binCount > 1) {
            val key = count.toLong() shl 32 or binCount.toLong()
            if (key != edgesKey) {
                edges = bandEdges(count, binCount, sampleRate, lowHz, highHz, scale)
                gains = bandGains(edges, binCount, sampleRate, trebleTilt, tiltCornerHz)
                falloffs = bandFalloffs(count, bassFalloff, falloff, bassSpread)
                edgesKey = key
            }
            var loudest = 0f
            var silent = true
            for (c in 0 until channels) {
                val channelTargets = targets[c]
                bandLevels(spectra[c], edges, levels)
                for (i in 0 until count) {
                    if (levels[i] >= NOISE_FLOOR) silent = false
                    val value = if (autoGain) {
                        levels[i] * gains[i] * sensitivity
                    } else {
                        (levels[i] - floorMagnitude).coerceAtLeast(0f) / gainRange * gains[i]
                    }
                    if (value > loudest) loudest = value
                    channelTargets[i] = value
                }
                applyFalloff(channelTargets, falloffs)
                for (i in 0 until count) {
                    channelTargets[i] = ((channelTargets[i] * headroom).coerceAtMost(1f) * heightPx).coerceAtLeast(minHeightPx)
                }
            }
            if (autoGain) adjustSensitivity(loudest, silent, deltaMs)
        } else {
            for (c in 0 until channels) targets[c].fill(minHeightPx)
        }

        val frames = deltaMs.toFloat() / FRAME_MS
        val riseAlpha = 1f - riseNoiseReduction.pow(frames)
        val gravity = 2f * heightPx / (fallDurationMs * fallDurationMs)
        for (c in 0 until channels) {
            val channelTargets = targets[c]
            val channelVelocities = velocities[c]
            val channelHeights = heights[c]
            for (i in 0 until count) {
                val target = channelTargets[i]
                val current = channelHeights[i]
                if (target >= current) {
                    channelHeights[i] = current + (target - current) * riseAlpha
                    channelVelocities[i] = 0f
                } else {
                    channelVelocities[i] += gravity * deltaMs
                    channelHeights[i] = (current - channelVelocities[i] * deltaMs).coerceAtLeast(target)
                    if (channelHeights[i] <= target) channelVelocities[i] = 0f
                }
            }
        }
    }

    internal fun currentTargets(channel: Int = 0): FloatArray = targets.getOrNull(channel)?.copyOf() ?: FloatArray(0)

    private fun adjustSensitivity(loudest: Float, silent: Boolean, deltaMs: Long) {
        if (silent) return
        val frames = deltaMs.toFloat() / FRAME_MS
        if (loudest > 1f) {
            sensitivity *= SENSITIVITY_DROP.pow(frames)
            warmingUp = false
        } else {
            sensitivity *= (if (warmingUp) WARMUP_RISE else SENSITIVITY_RISE).pow(frames)
        }
        sensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
    }

    companion object {
        const val SAMPLE_RATE = 44100f
        const val LOW_HZ = 40f
        const val HIGH_HZ = 16000f
        const val FALL_DURATION_MS = 600f
        const val RISE_NOISE_REDUCTION = 0.3f
        const val TILT_CORNER_HZ = 250f
        const val FRAME_MS = 16f
        const val NOISE_FLOOR = 1e-4f
        const val MIN_SENSITIVITY = 1e-2f
        const val MAX_SENSITIVITY = 2000f
        const val SENSITIVITY_DROP = 0.97f
        const val SENSITIVITY_RISE = 1.002f
        const val WARMUP_RISE = 1.1f

        fun binWidthHz(binCount: Int, sampleRate: Float = SAMPLE_RATE): Float = sampleRate / 2f / binCount

        fun bandEdgeFrequencies(
            bandCount: Int,
            sampleRate: Float = SAMPLE_RATE,
            lowHz: Float = LOW_HZ,
            highHz: Float = HIGH_HZ,
            scale: FrequencyScale = FrequencyScale.Log
        ): FloatArray {
            val high = highHz.coerceAtMost(sampleRate / 2f)
            val low = lowHz.coerceIn(1f, high)
            return when (scale) {
                FrequencyScale.Log -> {
                    val ratio = high / low
                    FloatArray(bandCount + 1) { i -> low * ratio.pow(i.toFloat() / bandCount) }
                }
                FrequencyScale.Linear -> FloatArray(bandCount + 1) { i -> low + (high - low) * i / bandCount }
            }
        }

        fun bandEdges(
            bandCount: Int,
            binCount: Int,
            sampleRate: Float = SAMPLE_RATE,
            lowHz: Float = LOW_HZ,
            highHz: Float = HIGH_HZ,
            scale: FrequencyScale = FrequencyScale.Log
        ): FloatArray {
            val width = binWidthHz(binCount, sampleRate)
            return bandEdgeFrequencies(bandCount, sampleRate, lowHz, highHz, scale).let { hz -> FloatArray(hz.size) { hz[it] / width } }
        }

        fun bandGains(
            edges: FloatArray,
            binCount: Int,
            sampleRate: Float = SAMPLE_RATE,
            tilt: Float,
            cornerHz: Float = TILT_CORNER_HZ
        ): FloatArray {
            val width = binWidthHz(binCount, sampleRate)
            return FloatArray(edges.size - 1) { i ->
                val centerHz = sqrt(edges[i] * edges[i + 1]) * width
                if (centerHz <= cornerHz) 1f else (centerHz / cornerHz).pow(tilt)
            }
        }

        fun bandFalloffs(bandCount: Int, bassFalloff: Float, falloff: Float, bassSpread: Float): FloatArray {
            val span = (bandCount * bassSpread).coerceAtLeast(1f)
            return FloatArray(bandCount) { i ->
                val t = (i / span).coerceAtMost(1f)
                bassFalloff + (falloff - bassFalloff) * t
            }
        }

        fun bandLevels(fft: FloatArray, edges: FloatArray): FloatArray =
            bandLevels(fft, edges, FloatArray(edges.size - 1))

        fun bandLevels(fft: FloatArray, edges: FloatArray, out: FloatArray): FloatArray {
            val bandCount = edges.size - 1
            val lastBin = fft.size - 1
            val firstBin = 1.coerceAtMost(lastBin)
            for (i in 0 until bandCount) {
                val lo = edges[i]
                val hi = edges[i + 1]
                val level = if (hi - lo < 1f) {
                    val center = ((lo + hi) / 2f).coerceIn(firstBin.toFloat(), lastBin.toFloat())
                    val index = floor(center).toInt()
                    val next = (index + 1).coerceAtMost(lastBin)
                    val fraction = center - index
                    fft[index] * (1f - fraction) + fft[next] * fraction
                } else {
                    val start = floor(lo).toInt().coerceIn(firstBin, lastBin)
                    val end = (ceil(hi).toInt() - 1).coerceIn(start, lastBin)
                    var peak = 0f
                    for (j in start..end) if (fft[j] > peak) peak = fft[j]
                    peak
                }
                out[i] = level
            }
            return out
        }

        fun applyFalloff(values: FloatArray, falloff: Float) {
            applyFalloff(values, FloatArray(values.size) { falloff })
        }

        fun applyFalloff(values: FloatArray, falloffs: FloatArray) {
            for (i in 1 until values.size) {
                values[i] = maxOf(values[i], values[i - 1] / falloffs[i - 1])
            }
            for (i in values.size - 2 downTo 0) {
                values[i] = maxOf(values[i], values[i + 1] / falloffs[i])
            }
        }
    }
}
