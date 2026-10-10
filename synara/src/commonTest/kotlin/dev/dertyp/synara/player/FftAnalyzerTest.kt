package dev.dertyp.synara.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FftAnalyzerTest {
    private val bufferSize = 256

    private fun sine(bin: Int, amplitude: Int = 10_000, length: Int = bufferSize) =
        ShortArray(length) { (amplitude * sin(2.0 * PI * bin * it / bufferSize)).toInt().toShort() }

    @Test
    fun outputHasHalfTheBufferSize() {
        assertEquals(bufferSize / 2, FftAnalyzer(bufferSize).getMagnitudes(ShortArray(bufferSize)).size)
        assertEquals(32, FftAnalyzer(64).getMagnitudes(ShortArray(64)).size)
        assertEquals(512, FftAnalyzer().getMagnitudes(ShortArray(1024)).size)
        assertEquals(bufferSize / 2, FftAnalyzer(bufferSize).fftData.value.size)
    }

    @Test
    fun silenceGivesZeros() {
        val magnitudes = FftAnalyzer(bufferSize).getMagnitudes(ShortArray(bufferSize))

        assertContentEquals(FloatArray(bufferSize / 2), magnitudes)
    }

    @Test
    fun sinePeaksAtItsBin() {
        val analyzer = FftAnalyzer(bufferSize)

        for (bin in listOf(4, 16, 40, 100)) {
            val magnitudes = analyzer.getMagnitudes(sine(bin))
            assertEquals(bin, magnitudes.indices.maxBy { magnitudes[it] })
            assertTrue(magnitudes[bin] > 0f)
        }
    }

    @Test
    fun shorterInputIsZeroPadded() {
        val short = sine(bin = 16, length = 100)
        val padded = short.copyOf(bufferSize)

        val magnitudes = FftAnalyzer(bufferSize).getMagnitudes(short)

        assertEquals(bufferSize / 2, magnitudes.size)
        assertContentEquals(FftAnalyzer(bufferSize).getMagnitudes(padded), magnitudes)
    }

    @Test
    fun longerInputIsTruncated() {
        val long = sine(bin = 16, length = bufferSize * 3)
        val truncated = long.copyOf(bufferSize)

        val magnitudes = FftAnalyzer(bufferSize).getMagnitudes(long)

        assertEquals(bufferSize / 2, magnitudes.size)
        assertContentEquals(FftAnalyzer(bufferSize).getMagnitudes(truncated), magnitudes)
    }

    @Test
    fun emptyInputGivesZeros() {
        assertContentEquals(FloatArray(bufferSize / 2), FftAnalyzer(bufferSize).getMagnitudes(ShortArray(0)))
    }

    @Test
    fun analyzerStateDoesNotLeakBetweenCalls() {
        val analyzer = FftAnalyzer(bufferSize)
        analyzer.getMagnitudes(sine(40))

        assertContentEquals(FloatArray(bufferSize / 2), analyzer.getMagnitudes(ShortArray(10)))
    }

    @Test
    fun fullScaleInputStaysWithinZeroAndOne() {
        val analyzer = FftAnalyzer(bufferSize)
        val inputs = listOf(
            ShortArray(bufferSize) { Short.MAX_VALUE },
            ShortArray(bufferSize) { Short.MIN_VALUE },
            ShortArray(bufferSize) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE },
            ShortArray(bufferSize) { if (it % 16 < 8) Short.MAX_VALUE else Short.MIN_VALUE },
            sine(bin = 16, amplitude = Short.MAX_VALUE.toInt())
        )

        for (input in inputs) {
            val magnitudes = analyzer.getMagnitudes(input)
            assertTrue(magnitudes.all { it in 0f..1f })
            assertTrue(magnitudes.any { it > 0f })
        }
    }

    @Test
    fun analyzePublishesTheMagnitudes() {
        val analyzer = FftAnalyzer(bufferSize)
        val input = sine(16)

        analyzer.analyze(input)

        assertContentEquals(FftAnalyzer(bufferSize).getMagnitudes(input), analyzer.fftData.value)
    }

    @Test
    fun updateDataPublishesTheGivenArray() {
        val analyzer = FftAnalyzer(bufferSize)
        val data = floatArrayOf(0.25f, 0.5f, 0.75f)

        analyzer.updateData(data)

        assertSame(data, analyzer.fftData.value)
    }
}
