package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.settings.*
import kotlin.math.pow

interface VisualizerReaction {
    fun update(
        fft: FloatArray,
        isPlaying: Boolean,
        heights: FloatArray,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    )
}

const val DEFAULT_SAMPLE_RATE = 44100

fun speedFactor(speed: Float): Float = 2f.pow((speed.coerceIn(0f, 1f) - 0.5f) * 4f)

fun scaledBase(base: Float, speed: Float): Float = base.pow(speedFactor(speed))

fun VisualizerPreset.createReaction(sampleRate: Int): VisualizerReaction {
    val rate = (if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE).toFloat()
    val low = lowHz.coerceIn(VisualizerLimits.lowHz)
    val high = highHz.coerceIn(VisualizerLimits.highHz).coerceAtLeast(low + 1f)
    val floorDb = minDb.coerceIn(VisualizerLimits.minDb)
    val ceilingDb = maxDb.coerceIn(VisualizerLimits.maxDb).coerceAtLeast(floorDb + 1f)
    return when (reaction) {
        VisualizerStyle.Synara -> SynaraReaction(
            lowHz = low,
            highHz = high,
            scale = frequencyScale,
            autoGain = sensitivity == VisualizerSensitivity.Auto,
            minDb = floorDb,
            maxDb = ceilingDb,
            riseBase = scaledBase(SynaraReaction.RISE_BASE, riseSpeed),
            fallBase = scaledBase(SynaraReaction.FALL_BASE, fallSpeed),
            sampleRate = rate
        )
        VisualizerStyle.Monstercat -> MonstercatReaction(
            falloff = falloff.coerceIn(VisualizerLimits.falloff),
            bassFalloff = bassFalloff.coerceIn(VisualizerLimits.bassFalloff),
            fallDurationMs = MonstercatReaction.FALL_DURATION_MS / speedFactor(fallSpeed),
            riseNoiseReduction = scaledBase(MonstercatReaction.RISE_NOISE_REDUCTION, riseSpeed),
            trebleTilt = trebleTilt.coerceIn(VisualizerLimits.trebleTilt),
            sampleRate = rate,
            lowHz = low,
            highHz = high,
            scale = frequencyScale,
            autoGain = sensitivity == VisualizerSensitivity.Auto,
            minDb = floorDb,
            maxDb = ceilingDb
        )
    }
}
