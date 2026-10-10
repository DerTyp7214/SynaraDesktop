package dev.dertyp.synara.scrobble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScrobbleThresholdTest {

    @Test
    fun fourMinuteTrackIsScrobbledAtHalfItsDuration() {
        assertFalse(BaseScrobbler.isScrobbled(119.seconds, 240.seconds))
        assertTrue(BaseScrobbler.isScrobbled(120.seconds, 240.seconds))
    }

    @Test
    fun tenMinuteTrackIsScrobbledAfterThreeMinutes() {
        assertFalse(BaseScrobbler.isScrobbled(179.seconds, 10.minutes))
        assertTrue(BaseScrobbler.isScrobbled(180.seconds, 10.minutes))
    }

    @Test
    fun sixMinuteTrackIsScrobbledAfterThreeMinutes() {
        assertFalse(BaseScrobbler.isScrobbled(179.seconds, 6.minutes))
        assertTrue(BaseScrobbler.isScrobbled(180.seconds, 6.minutes))
        assertEquals(180.seconds, BaseScrobbler.requiredDuration(0.seconds, 6.minutes))
    }

    @Test
    fun requiredDurationIsHalfTheTrackMinusThePosition() {
        assertEquals(90.seconds, BaseScrobbler.requiredDuration(30.seconds, 4.minutes))
    }
}
