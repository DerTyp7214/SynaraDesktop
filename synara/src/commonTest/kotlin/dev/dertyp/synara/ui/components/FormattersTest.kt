package dev.dertyp.synara.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattersTest {
    @Test
    fun durationOfZeroIsZeroMinutesZeroSeconds() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun durationTruncatesPartialSeconds() {
        assertEquals("0:59", formatDuration(59_999))
    }

    @Test
    fun durationRollsOverToMinutesAtSixtySeconds() {
        assertEquals("1:00", formatDuration(60_000))
    }

    @Test
    fun durationJustBelowOneHourHasNoHourPart() {
        assertEquals("59:59", formatDuration(3_599_999))
    }

    @Test
    fun durationOfExactlyOneHourHasHourPartAndPaddedMinutes() {
        assertEquals("1:00:00", formatDuration(3_600_000))
    }

    @Test
    fun durationWithHoursMinutesAndSecondsPadsBoth() {
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun durationKeepsCountingHoursPastADay() {
        assertEquals("25:00:00", formatDuration(25 * 3_600_000L))
    }

    @Test
    fun listenedTimeBelowOneMinuteIsLessThanOneMinute() {
        assertEquals("<1m", formatListenedTime(59_999))
    }

    @Test
    fun listenedTimeOfZeroIsLessThanOneMinute() {
        assertEquals("<1m", formatListenedTime(0))
    }

    @Test
    fun listenedTimeOfOneMinuteShowsMinutesOnly() {
        assertEquals("1m", formatListenedTime(60_000))
    }

    @Test
    fun listenedTimeOfExactlyOneHourShowsZeroMinutes() {
        assertEquals("1h 0m", formatListenedTime(3_600_000))
    }

    @Test
    fun listenedTimeShowsHoursAndMinutes() {
        assertEquals("1h 30m", formatListenedTime(5_400_000))
    }
}
