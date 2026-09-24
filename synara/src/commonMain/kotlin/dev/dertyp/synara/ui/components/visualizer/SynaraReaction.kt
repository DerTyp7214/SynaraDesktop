package dev.dertyp.synara.ui.components.visualizer

import kotlin.math.log10
import kotlin.math.pow

class SynaraReaction : VisualizerReaction {
    override val mirrored = true

    override fun update(
        fft: FloatArray,
        isPlaying: Boolean,
        heights: FloatArray,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    ) {
        val fftSize = fft.size
        val maxFftBins = fftSize / 2
        val binSize = if (bandCount > 0) maxFftBins.toFloat() / bandCount else 0f

        val minDb = -60f
        val maxDb = -20f

        val lerpFactor = (deltaMs / 12.5f).coerceIn(0f, 1f)
        val riseAlpha = 1f - 0.2f.pow(lerpFactor)
        val fallAlpha = 1f - 0.88f.pow(lerpFactor)

        for (i in 0 until bandCount.coerceAtMost(heights.size)) {
            var maxMagnitude = 0f
            if (isPlaying && fft.isNotEmpty() && binSize > 0) {
                val startBin = (i * binSize).toInt()
                val endBin = ((i + 1) * binSize).toInt().coerceAtMost(maxFftBins - 1)

                for (j in startBin..endBin) {
                    maxMagnitude = maxOf(maxMagnitude, fft[j])
                }
            }

            val targetHeight = if (isPlaying) {
                val db = if (maxMagnitude > 0.00003f) 20f * log10(maxMagnitude) else minDb
                val targetNormalized = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                (targetNormalized * heightPx).coerceAtLeast(minHeightPx)
            } else {
                minHeightPx
            }

            val prev = heights[i]
            val alpha = if (targetHeight > prev) riseAlpha else fallAlpha
            heights[i] = prev + (targetHeight - prev) * alpha
        }
    }
}
