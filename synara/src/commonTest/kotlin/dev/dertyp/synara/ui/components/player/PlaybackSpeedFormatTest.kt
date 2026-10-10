package dev.dertyp.synara.ui.components.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedFormatTest {
    @Test
    fun wholeSpeedHasNoFraction() {
        assertEquals("1", formatPlaybackSpeed(1f))
        assertEquals("2", formatPlaybackSpeed(2f))
    }

    @Test
    fun tenthsDropTheTrailingZero() {
        assertEquals("1.5", formatPlaybackSpeed(1.5f))
        assertEquals("1.1", formatPlaybackSpeed(1.1f))
    }

    @Test
    fun hundredthsKeepBothDigits() {
        assertEquals("1.25", formatPlaybackSpeed(1.25f))
    }

    @Test
    fun smallHundredthsKeepTheLeadingZero() {
        assertEquals("1.05", formatPlaybackSpeed(1.05f))
    }

    @Test
    fun speedBelowOneStartsWithZero() {
        assertEquals("0.75", formatPlaybackSpeed(0.75f))
        assertEquals("0.5", formatPlaybackSpeed(0.5f))
    }
}
