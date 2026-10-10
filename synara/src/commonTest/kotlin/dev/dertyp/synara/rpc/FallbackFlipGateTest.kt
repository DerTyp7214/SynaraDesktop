package dev.dertyp.synara.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackFlipGateTest {
    private var clock = 0L
    private val gate = FallbackFlipGate(2000) { clock }

    @Test
    fun firstSignalFlips() {
        assertTrue(gate.shouldFlip())
    }

    @Test
    fun burstAtTheSameInstantFlipsOnce() {
        val flips = List(3) { gate.shouldFlip() }

        assertEquals(listOf(true, false, false), flips)
    }

    @Test
    fun signalsInsideTheWindowDoNotFlip() {
        assertTrue(gate.shouldFlip())

        clock = 1
        assertFalse(gate.shouldFlip())
        clock = 1999
        assertFalse(gate.shouldFlip())
    }

    @Test
    fun signalAtTheEndOfTheWindowFlipsAgain() {
        assertTrue(gate.shouldFlip())

        clock = 2000
        assertTrue(gate.shouldFlip())
    }

    @Test
    fun ignoredSignalsDoNotExtendTheWindow() {
        assertTrue(gate.shouldFlip())

        clock = 1500
        assertFalse(gate.shouldFlip())
        clock = 2000
        assertTrue(gate.shouldFlip())
    }

    @Test
    fun windowIsMeasuredFromTheLastFlip() {
        clock = 5000
        assertTrue(gate.shouldFlip())
        clock = 7000
        assertTrue(gate.shouldFlip())

        clock = 8999
        assertFalse(gate.shouldFlip())
        clock = 9000
        assertTrue(gate.shouldFlip())
    }

    @Test
    fun eachFailureBurstFlipsOnce() {
        val flips = mutableListOf<Boolean>()
        for (burstStart in listOf(0L, 6000L, 12000L)) {
            for (offset in listOf(0L, 1L, 2L)) {
                clock = burstStart + offset
                flips += gate.shouldFlip()
            }
        }

        assertEquals(3, flips.count { it })
        assertEquals(listOf(true, false, false, true, false, false, true, false, false), flips)
    }
}
