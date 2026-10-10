package dev.dertyp.synara.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioIntensityTest {

    @Test
    fun emptySpectrumGivesZero() {
        assertEquals(0f, audioIntensity(FloatArray(0)))
    }

    @Test
    fun silentSpectrumGivesZero() {
        assertEquals(0f, audioIntensity(FloatArray(512)))
        assertEquals(0f, audioIntensity(FloatArray(5)))
    }

    @Test
    fun binsFromTheFifthOnAreIgnored() {
        val low = floatArrayOf(0.1f, 0.05f, 0.02f, 0.01f, 0.03f)
        val withHighBins = low + FloatArray(100) { 1f }

        assertEquals(audioIntensity(low), audioIntensity(withHighBins))
        assertEquals(0f, audioIntensity(FloatArray(5) + FloatArray(100) { 1f }))
    }

    @Test
    fun lowestBinWeighsMoreThanTheFifthBin() {
        val inLowestBin = audioIntensity(floatArrayOf(0.1f, 0f, 0f, 0f, 0f))
        val inFifthBin = audioIntensity(floatArrayOf(0f, 0f, 0f, 0f, 0.1f))

        assertTrue(inFifthBin > 0f)
        assertTrue(inLowestBin > inFifthBin)
    }

    @Test
    fun weightFallsWithEveryBin() {
        val intensities = List(5) { bin -> audioIntensity(FloatArray(5).also { it[bin] = 0.1f }) }

        intensities.zipWithNext().forEach { (lower, higher) -> assertTrue(lower > higher) }
    }

    @Test
    fun fullScaleSpectrumGivesOne() {
        assertEquals(1f, audioIntensity(FloatArray(5) { 1f }))
        assertEquals(1f, audioIntensity(FloatArray(512) { 1f }))
    }

    @Test
    fun spectraWithFewerThanFiveBinsAreHandled() {
        assertEquals(1f, audioIntensity(floatArrayOf(1f)))
        assertEquals(1f, audioIntensity(floatArrayOf(1f, 1f)))
        assertEquals(1f, audioIntensity(floatArrayOf(1f, 1f, 1f, 1f)))
        assertEquals(0f, audioIntensity(floatArrayOf(0f)))
        assertEquals(0f, audioIntensity(floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun intensityGrowsWithTheMagnitude() {
        val magnitudes = listOf(0f, 0.0001f, 0.001f, 0.003f, 0.01f, 0.03f, 0.1f, 0.2f, 0.5f, 1f)
        val intensities = magnitudes.map { magnitude -> audioIntensity(FloatArray(5) { magnitude }) }

        intensities.zipWithNext().forEach { (lower, higher) -> assertTrue(lower <= higher) }
        assertTrue(intensities.all { it in 0f..1f })
        val audible = listOf(0.001f, 0.01f, 0.1f).map { magnitude -> audioIntensity(FloatArray(5) { magnitude }) }
        audible.zipWithNext().forEach { (lower, higher) -> assertTrue(lower < higher) }
    }
}
