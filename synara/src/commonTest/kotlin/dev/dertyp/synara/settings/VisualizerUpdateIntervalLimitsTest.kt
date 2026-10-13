package dev.dertyp.synara.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualizerUpdateIntervalLimitsTest {

    @Test
    fun intervalIsClampedToOneToSixteenMilliseconds() {
        assertEquals(1, VisualizerUpdateIntervalLimits.DEFAULT_INTERVAL_MILLIS)
        assertEquals(1..16, VisualizerUpdateIntervalLimits.intervalMillis)
        assertEquals(1, VisualizerUpdateIntervalLimits.clampInterval(-5))
        assertEquals(1, VisualizerUpdateIntervalLimits.clampInterval(0))
        assertEquals(1, VisualizerUpdateIntervalLimits.clampInterval(1))
        assertEquals(4, VisualizerUpdateIntervalLimits.clampInterval(4))
        assertEquals(16, VisualizerUpdateIntervalLimits.clampInterval(16))
        assertEquals(16, VisualizerUpdateIntervalLimits.clampInterval(17))
    }
}
