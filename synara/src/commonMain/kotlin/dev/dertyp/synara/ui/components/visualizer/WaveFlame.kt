package dev.dertyp.synara.ui.components.visualizer

import kotlin.math.abs

internal fun blendFlames(
    flames: FloatArray,
    n: Int,
    window: Int,
    wrap: Boolean,
    blended: FloatArray,
    sums: FloatArray
) {
    for (i in 0 until n) {
        var weighted = 0f
        var weights = 0f
        var sum = 0f
        for (d in -window..window) {
            val k = if (wrap) ((i + d) % n + n) % n else i + d
            if (k !in 0 until n) continue
            val weight = (window + 1 - abs(d)).toFloat()
            val flame = flames[k]
            weighted += flame * weight
            weights += weight
            sum += flame
        }
        blended[i] = if (weights > 0f) weighted / weights else 0f
        sums[i] = sum
    }
}
