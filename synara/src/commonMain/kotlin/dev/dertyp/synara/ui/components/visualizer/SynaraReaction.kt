package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.settings.FrequencyScale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class SynaraReaction(
    private val lowHz: Float = 0f,
    private val highHz: Float = DEFAULT_SAMPLE_RATE / 4f,
    private val scale: FrequencyScale = FrequencyScale.Linear,
    private val autoGain: Boolean = false,
    private val minDb: Float = MIN_DB,
    private val maxDb: Float = MAX_DB,
    private val riseBase: Float = RISE_BASE,
    private val fallBase: Float = FALL_BASE,
    private val sampleRate: Float = DEFAULT_SAMPLE_RATE.toFloat()
) : VisualizerReaction {
    var ceilingDb = maxDb
        private set

    private var warmingUp = true
    private var edges = FloatArray(0)
    private var edgesKey = -1L

    override fun update(
        fft: FloatArray,
        isPlaying: Boolean,
        heights: FloatArray,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    ) {
        val count = bandCount.coerceAtMost(heights.size)
        if (count <= 0) return
        val binCount = fft.size
        val active = isPlaying && binCount > 1

        if (active) {
            val key = count.toLong() shl 32 or binCount.toLong()
            if (key != edgesKey) {
                edges = bandEdgeBins(count, binCount, sampleRate, lowHz, highHz, scale)
                edgesKey = key
            }
        }

        val range = maxDb - minDb
        val floorDb = if (autoGain) ceilingDb - range else minDb
        val topDb = if (autoGain) ceilingDb else maxDb

        val lerpFactor = (deltaMs / 12.5f).coerceIn(0f, 1f)
        val riseAlpha = 1f - riseBase.pow(lerpFactor)
        val fallAlpha = 1f - fallBase.pow(lerpFactor)

        val lastBin = if (active) lastBinOf(edges, binCount) else 0
        var loudest = 0f

        for (i in 0 until count) {
            val targetHeight = if (active) {
                val startBin = floor(edges[i]).toInt().coerceIn(0, lastBin)
                val endBin = floor(edges[i + 1]).toInt().coerceIn(startBin, lastBin)
                var maxMagnitude = 0f
                for (j in startBin..endBin) {
                    maxMagnitude = maxOf(maxMagnitude, fft[j])
                }
                if (maxMagnitude > loudest) loudest = maxMagnitude
                val db = if (maxMagnitude > SILENCE) 20f * log10(maxMagnitude) else floorDb
                val targetNormalized = ((db - floorDb) / (topDb - floorDb)).coerceIn(0f, 1f)
                (targetNormalized * heightPx).coerceAtLeast(minHeightPx)
            } else {
                minHeightPx
            }

            val prev = heights[i]
            val alpha = if (targetHeight > prev) riseAlpha else fallAlpha
            heights[i] = prev + (targetHeight - prev) * alpha
        }

        if (autoGain && active && loudest > SILENCE) adjustCeiling(20f * log10(loudest), deltaMs)
    }

    private fun adjustCeiling(loudestDb: Float, deltaMs: Long) {
        val frames = deltaMs / FRAME_MS
        val target = loudestDb + AUTO_HEADROOM_DB
        ceilingDb = if (target > ceilingDb) {
            warmingUp = false
            ceilingDb + (target - ceilingDb) * (1f - AUTO_ATTACK.pow(frames))
        } else {
            ceilingDb - (if (warmingUp) AUTO_WARMUP_RELEASE_DB else AUTO_RELEASE_DB) * frames
        }
        ceilingDb = ceilingDb.coerceIn(AUTO_MIN_CEILING_DB, 0f)
    }

    companion object {
        const val RISE_BASE = 0.2f
        const val FALL_BASE = 0.88f
        const val MIN_DB = -60f
        const val MAX_DB = -20f
        const val SILENCE = 0.00003f
        const val FRAME_MS = 16f
        const val AUTO_ATTACK = 0.7f
        const val AUTO_RELEASE_DB = 0.03f
        const val AUTO_WARMUP_RELEASE_DB = 0.5f
        const val AUTO_HEADROOM_DB = 3f
        const val AUTO_MIN_CEILING_DB = -50f

        fun bandEdgeBins(
            bandCount: Int,
            binCount: Int,
            sampleRate: Float,
            lowHz: Float,
            highHz: Float,
            scale: FrequencyScale
        ): FloatArray {
            val binWidth = sampleRate / 2f / binCount
            val high = highHz.coerceAtMost(sampleRate / 2f) / binWidth
            val low = (lowHz / binWidth).coerceIn(0f, high)
            return when (scale) {
                FrequencyScale.Linear -> FloatArray(bandCount + 1) { i -> low + (high - low) * i / bandCount }
                FrequencyScale.Log -> {
                    val start = low.coerceIn(1f, high)
                    val ratio = high / start
                    FloatArray(bandCount + 1) { i -> start * ratio.pow(i.toFloat() / bandCount) }
                }
            }
        }

        private fun lastBinOf(edges: FloatArray, binCount: Int): Int =
            (ceil(edges.last()).toInt() - 1).coerceIn(0, binCount - 1)
    }
}
