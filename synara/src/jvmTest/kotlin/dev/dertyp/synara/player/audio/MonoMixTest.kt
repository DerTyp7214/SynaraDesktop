package dev.dertyp.synara.player.audio

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonoMixTest {
    private val tolerance = 1e-5f
    private val attenuation = (1.0 / sqrt(2.0)).toFloat()

    private fun assertClose(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index], tolerance, "index $index")
        }
    }

    @Test
    fun monoFeedsBothSidesAtFullLevel() {
        val (left, right) = MonoMix.sideCoefficients(1)
        assertClose(floatArrayOf(1f), left)
        assertClose(floatArrayOf(1f), right)
    }

    @Test
    fun stereoSidesAreTheRawChannels() {
        val (left, right) = MonoMix.sideCoefficients(2)
        assertContentEquals(floatArrayOf(1f, 0f), left)
        assertContentEquals(floatArrayOf(0f, 1f), right)
    }

    @Test
    fun surroundRoutesCentreToBothSidesDropsLfeAndKeepsSurroundsOnTheirSide() {
        val (left, right) = MonoMix.sideCoefficients(6)
        val gain = MonoMix.loudnessCompensation(6).toFloat()
        val norm = 1f + 2f * attenuation
        val front = gain / norm
        val shared = gain * attenuation / norm

        assertClose(floatArrayOf(front, 0f, shared, 0f, shared, 0f), left)
        assertClose(floatArrayOf(0f, front, shared, 0f, 0f, shared), right)
        assertEquals(0f, left[3])
        assertEquals(0f, right[3])
        assertEquals(gain, left.sum(), tolerance)
        assertEquals(gain, right.sum(), tolerance)
    }

    @Test
    fun sidesAverageToTheMonoCoefficients() {
        (1..8).forEach { channels ->
            val (left, right) = MonoMix.sideCoefficients(channels)
            val mix = MonoMix.coefficients(channels)
            mix.indices.forEach { index ->
                assertEquals(mix[index].toFloat(), (left[index] + right[index]) / 2f, tolerance, "$channels channels, index $index")
            }
        }
    }

    @Test
    fun coefficientsStayStableAcrossCachedCalls() {
        val first = MonoMix.coefficients(6)
        val second = MonoMix.coefficients(6)
        assertContentEquals(first, second)
        assertContentEquals(doubleArrayOf(0.5, 0.5), MonoMix.coefficients(2))
    }

    @Test
    fun noChannelsGivesEmptySides() {
        val (left, right) = MonoMix.sideCoefficients(0)
        assertTrue(left.isEmpty())
        assertTrue(right.isEmpty())
    }
}
