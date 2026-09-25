package dev.dertyp.synara.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.log10

fun audioIntensity(fft: FloatArray): Float {
    if (fft.isEmpty()) return 0f

    var weightedSum = 0f
    var weightTotal = 0f
    val limit = if (fft.size < 5) fft.size else 5
    for (i in 0 until limit) {
        val freqWeight = (5 - i).toFloat().let { it * it }
        val curvedValue = fft[i] * fft[i]
        weightedSum += curvedValue * freqWeight
        weightTotal += freqWeight
    }

    val avgPower = if (weightTotal > 0f) weightedSum / weightTotal else 0f

    val minDb = -90f
    val maxDb = -10f
    val db = if (avgPower > 1e-9f) 10f * log10(avgPower) else minDb
    val normalized = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)

    return normalized * normalized
}

fun Flow<FloatArray>.audioIntensityIn(scope: CoroutineScope): StateFlow<Float> =
    map(::audioIntensity).stateIn(scope, SharingStarted.Lazily, 0f)
