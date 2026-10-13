package dev.dertyp.synara.settings

object VisualizerUpdateIntervalLimits {
    const val DEFAULT_INTERVAL_MILLIS = 1
    val intervalMillis = 1..16

    fun clampInterval(millis: Int): Int = millis.coerceIn(intervalMillis)
}
