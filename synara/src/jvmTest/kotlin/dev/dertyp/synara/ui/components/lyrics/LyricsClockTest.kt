package dev.dertyp.synara.ui.components.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsClockTest {

    private fun ms(value: Long) = value * 1_000_000L

    @Test
    fun extrapolatesBetweenReports() {
        val clock = LyricsClock()
        clock.start(10_000, playing = true, nowNanos = ms(0))
        assertEquals(10_000, clock.positionAt(ms(0)))
        assertEquals(10_016, clock.positionAt(ms(16)))
        assertEquals(10_100, clock.positionAt(ms(100)))
    }

    @Test
    fun matchingReportsKeepTheExtrapolation() {
        val clock = LyricsClock()
        clock.start(0, playing = true, nowNanos = ms(0))
        clock.onPosition(80, ms(80))
        assertEquals(96, clock.positionAt(ms(96)))
    }

    @Test
    fun smallDriftIsCorrectedGradually() {
        val clock = LyricsClock()
        clock.start(0, playing = true, nowNanos = ms(0))
        clock.onPosition(180, ms(100))
        assertEquals(120, clock.positionAt(ms(100)))
        clock.onPosition(120, ms(200))
        assertEquals(195, clock.positionAt(ms(200)))
    }

    @Test
    fun driftBeyondTheLimitSnaps() {
        val clock = LyricsClock()
        clock.start(0, playing = true, nowNanos = ms(0))
        clock.onPosition(500, ms(100))
        assertEquals(500, clock.positionAt(ms(100)))
        clock.onPosition(0, ms(200))
        assertEquals(0, clock.positionAt(ms(200)))
        assertEquals(50, clock.positionAt(ms(250)))
    }

    @Test
    fun seekSnapsAndIgnoresStaleReports() {
        val clock = LyricsClock()
        clock.start(5_000, playing = true, nowNanos = ms(0))
        clock.onSeek(60_000, ms(100))
        assertEquals(60_000, clock.positionAt(ms(100)))
        clock.onPosition(5_120, ms(120))
        assertEquals(60_050, clock.positionAt(ms(150)))
        clock.onPosition(60_100, ms(200))
        assertEquals(60_100, clock.positionAt(ms(200)))
        clock.onPosition(5_000, ms(1_200))
        assertEquals(5_000, clock.positionAt(ms(1_200)))
    }

    @Test
    fun pausedClockHoldsTheReportedPosition() {
        val clock = LyricsClock()
        clock.start(42_000, playing = false, nowNanos = ms(0))
        assertEquals(42_000, clock.positionAt(ms(5_000)))
        clock.onPosition(41_000, ms(6_000))
        assertEquals(41_000, clock.positionAt(ms(9_000)))
    }

    @Test
    fun timeBeforeTheAnchorDoesNotRewind() {
        val clock = LyricsClock()
        clock.start(1_000, playing = true, nowNanos = ms(100))
        assertEquals(1_000, clock.positionAt(ms(50)))
    }
}
