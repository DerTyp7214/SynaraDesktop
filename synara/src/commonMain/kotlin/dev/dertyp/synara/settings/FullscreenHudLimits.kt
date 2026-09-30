package dev.dertyp.synara.settings

object FullscreenHudLimits {
    const val DEFAULT_DELAY_SECONDS = 3
    val delaySeconds = 1..30

    fun clampDelay(seconds: Int): Int = seconds.coerceIn(delaySeconds)
}
