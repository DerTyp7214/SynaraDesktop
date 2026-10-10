package dev.dertyp.synara.rpc

import dev.dertyp.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerClockTest {
    private val clock = ServerClock()

    @Test
    fun freshClockHasNoOffset() {
        assertNull(clock.offsetMs.value)
    }

    @Test
    fun offsetIsServerTimeMinusTheMidpointOfTheRoundTrip() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        assertEquals(4_000L, clock.offsetMs.value)
    }

    @Test
    fun roundTripAboveFiveSecondsIsIgnored() {
        clock.record(sentAt = 0, serverTime = 9_000, receivedAt = 5_001)

        assertNull(clock.offsetMs.value)
    }

    @Test
    fun roundTripOfExactlyFiveSecondsIsAccepted() {
        clock.record(sentAt = 0, serverTime = 9_000, receivedAt = 5_000)

        assertEquals(6_500L, clock.offsetMs.value)
    }

    @Test
    fun negativeRoundTripIsIgnored() {
        clock.record(sentAt = 1_000, serverTime = 9_000, receivedAt = 999)

        assertNull(clock.offsetMs.value)
    }

    @Test
    fun slowerSampleLeavesTheOffset() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        clock.record(sentAt = 1_000, serverTime = 9_000, receivedAt = 1_200)

        assertEquals(4_000L, clock.offsetMs.value)
    }

    @Test
    fun fasterSampleReplacesTheOffset() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        clock.record(sentAt = 1_000, serverTime = 7_025, receivedAt = 1_050)

        assertEquals(6_000L, clock.offsetMs.value)
    }

    @Test
    fun equallyFastSampleReplacesTheOffset() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        clock.record(sentAt = 2_000, serverTime = 9_050, receivedAt = 2_100)

        assertEquals(7_000L, clock.offsetMs.value)
    }

    @Test
    fun resetClearsTheOffsetAndAcceptsASlowerSampleAgain() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        clock.reset()

        assertNull(clock.offsetMs.value)

        clock.record(sentAt = 1_000, serverTime = 9_100, receivedAt = 1_200)

        assertEquals(8_000L, clock.offsetMs.value)
    }

    @Test
    fun serverNowIsTheLocalTimeShiftedByTheOffset() {
        clock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)

        val before = currentTimeMillis()
        val serverNow = clock.serverNow()
        val after = currentTimeMillis()

        assertTrue(serverNow >= before + 4_000)
        assertTrue(serverNow <= after + 4_000)
    }

    @Test
    fun serverNowIsTheLocalTimeWithoutAnOffset() {
        val before = currentTimeMillis()
        val serverNow = clock.serverNow()
        val after = currentTimeMillis()

        assertTrue(serverNow in before..after)
    }
}
