package dev.dertyp.synara.ui.components.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimecodeFormatTest {
    @Test
    fun formatShowsMillisecondsAsThreeDigitFraction() {
        assertEquals("1:01.500", formatTimecode(61_500))
    }

    @Test
    fun formatOmitsTheFractionOnWholeSeconds() {
        assertEquals("1:01", formatTimecode(61_000))
    }

    @Test
    fun formatPadsSmallFractionsToThreeDigits() {
        assertEquals("0:00.007", formatTimecode(7))
    }

    @Test
    fun formatAppendsTheFractionAfterTheHourPart() {
        assertEquals("1:00:00.500", formatTimecode(3_600_500))
    }

    @Test
    fun parseReadsFractionalSecondsAfterMinutes() {
        assertEquals(61_500L, parseTimecode("1:01.5"))
    }

    @Test
    fun parseReadsBareSeconds() {
        assertEquals(90_000L, parseTimecode("90"))
    }

    @Test
    fun parseReadsMinutesAndSeconds() {
        assertEquals(90_000L, parseTimecode("1:30"))
    }

    @Test
    fun parseIgnoresSurroundingWhitespace() {
        assertEquals(90_000L, parseTimecode("  1:30  "))
    }

    @Test
    fun parseReadsHoursMinutesAndSeconds() {
        assertEquals(3_723_000L, parseTimecode("1:2:3"))
    }

    @Test
    fun parseRejectsSecondsOfSixtyOrMoreBehindAUnit() {
        assertNull(parseTimecode("1:75"))
        assertNull(parseTimecode("1:60"))
    }

    @Test
    fun parseAcceptsSixtySecondsOrMoreWithoutAUnit() {
        assertEquals(75_000L, parseTimecode("75"))
    }

    @Test
    fun parseRejectsMoreThanThreeParts() {
        assertNull(parseTimecode("1:2:3:4"))
    }

    @Test
    fun parseRejectsNotANumberAndInfinity() {
        assertNull(parseTimecode("NaN"))
        assertNull(parseTimecode("Infinity"))
        assertNull(parseTimecode("-Infinity"))
        assertNull(parseTimecode("1:NaN"))
        assertNull(parseTimecode("1:2:Infinity"))
    }

    @Test
    fun parseRejectsEmptyInput() {
        assertNull(parseTimecode(""))
        assertNull(parseTimecode("   "))
    }

    @Test
    fun parseRejectsNegativeSeconds() {
        assertNull(parseTimecode("-1"))
    }

    @Test
    fun parseRejectsNonNumericUnits() {
        assertNull(parseTimecode("a:10"))
    }

    @Test
    fun parseRejectsNegativeUnits() {
        assertNull(parseTimecode("-1:10"))
    }

    @Test
    fun parseRejectsFractionalUnits() {
        assertNull(parseTimecode("1.5:10"))
    }

    @Test
    fun parseRejectsNonNumericSeconds() {
        assertNull(parseTimecode("1:xx"))
    }

    @Test
    fun formatThenParseRoundTripsEveryMillisecondFraction() {
        for (fraction in 0L..999L) {
            val ms = 61_000L + fraction
            assertEquals(ms, parseTimecode(formatTimecode(ms)), "formatTimecode($ms) = ${formatTimecode(ms)}")
        }
    }

    @Test
    fun formatThenParseRoundTripsAcrossMinutesAndHours() {
        val values = listOf(0L, 1_000L, 59_000L, 60_000L, 3_599_000L, 3_600_000L, 3_723_500L, 36_000_000L)
        for (ms in values) {
            assertEquals(ms, parseTimecode(formatTimecode(ms)), "formatTimecode($ms) = ${formatTimecode(ms)}")
        }
    }
}
