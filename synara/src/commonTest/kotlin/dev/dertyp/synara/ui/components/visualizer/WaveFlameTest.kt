package dev.dertyp.synara.ui.components.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveFlameTest {
    private val eps = 0.0001f

    @Test
    fun noFlamesBlendToZero() {
        val flames = FloatArray(10)
        val blended = FloatArray(10) { 1f }
        val sums = FloatArray(10) { 1f }
        blendFlames(flames, 10, 3, wrap = false, blended, sums)
        for (i in 0 until 10) {
            assertEquals(0f, blended[i], eps)
            assertEquals(0f, sums[i], eps)
        }
    }

    @Test
    fun singleFlameSpreadsSymmetricallyAndFades() {
        val flames = FloatArray(20).also { it[10] = 1f }
        val blended = FloatArray(20)
        val sums = FloatArray(20)
        blendFlames(flames, 20, 3, wrap = false, blended, sums)
        assertEquals(4f / 16f, blended[10], eps)
        for (d in 1..3) {
            assertEquals(blended[10 - d], blended[10 + d], eps)
            assertTrue(blended[10 + d] < blended[10 + d - 1])
            assertTrue(blended[10 + d] > 0f)
            assertEquals(1f, sums[10 + d], eps)
        }
        assertEquals(0f, blended[14], eps)
        assertEquals(0f, blended[6], eps)
    }

    @Test
    fun uniformFlamesStayUniformAtTheEdges() {
        val flames = FloatArray(8) { 0.5f }
        val blended = FloatArray(8)
        val sums = FloatArray(8)
        blendFlames(flames, 8, 3, wrap = false, blended, sums)
        for (i in 0 until 8) assertEquals(0.5f, blended[i], eps)
        assertEquals(2f, sums[0], eps)
        assertEquals(3.5f, sums[4], eps)
    }

    @Test
    fun wrapCarriesFlamesAcrossTheSeam() {
        val flames = FloatArray(20).also { it[0] = 1f }
        val blended = FloatArray(20)
        val sums = FloatArray(20)
        blendFlames(flames, 20, 3, wrap = true, blended, sums)
        assertEquals(blended[1], blended[19], eps)
        assertEquals(blended[3], blended[17], eps)
        assertTrue(blended[17] > 0f)
        assertEquals(0f, blended[16], eps)

        val open = FloatArray(20)
        blendFlames(flames, 20, 3, wrap = false, open, sums)
        assertEquals(0f, open[19], eps)
    }
}
