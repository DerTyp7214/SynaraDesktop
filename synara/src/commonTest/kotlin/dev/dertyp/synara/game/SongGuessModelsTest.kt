package dev.dertyp.synara.game

import kotlin.test.Test
import kotlin.test.assertEquals

class SongGuessModelsTest {

    @Test
    fun pointsFallByOnePerAttemptAndNeverBelowZero() {
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), (0..6).map { pointsForAttempt(it) })
    }

    @Test
    fun audioStartFloorIgnoresMissingAndNegligibleOffsetsAndLeadsTheAudibleStart() {
        assertEquals(0L, audioStartFloorMs(null))
        assertEquals(0L, audioStartFloorMs(50L))
        assertEquals(1L, audioStartFloorMs(51L))
        assertEquals(950L, audioStartFloorMs(1000L))
    }
}
