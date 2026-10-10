package dev.dertyp.synara.player.audio

import java.nio.ShortBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AudioIoConvertTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun shorts(buffer: ShortBuffer): ShortArray = ShortArray(buffer.remaining()) { buffer.get(buffer.position() + it) }

    private fun floatBytes(vararg values: Float): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            out[index * 4] = bits.toByte()
            out[index * 4 + 1] = (bits shr 8).toByte()
            out[index * 4 + 2] = (bits shr 16).toByte()
            out[index * 4 + 3] = (bits shr 24).toByte()
        }
        return out
    }

    @Test
    fun unsignedEightBitSamplesAreCentredAndScaledToSixteenBits() {
        val data = bytes(0x80, 0xFF, 0x00)
        val result = convertToShortBuffer(data, data.size, 8, unsigned8Bit = true)
        assertContentEquals(shortArrayOf(0, 0x7F00, -32768), shorts(result))
    }

    @Test
    fun signedEightBitSamplesAreScaledToSixteenBits() {
        val data = bytes(0x01, 0x7F, 0x80, 0xFF)
        val result = convertToShortBuffer(data, data.size, 8)
        assertContentEquals(shortArrayOf(0x0100, 0x7F00, -32768, -256), shorts(result))
    }

    @Test
    fun sixteenBitLittleEndianSamplesAreRead() {
        val data = bytes(0x34, 0x12, 0xFF, 0xFF, 0x00, 0x80)
        val result = convertToShortBuffer(data, data.size, 16)
        assertContentEquals(shortArrayOf(0x1234, -1, -32768), shorts(result))
    }

    @Test
    fun sixteenBitBigEndianSamplesAreRead() {
        val data = bytes(0x12, 0x34, 0xFF, 0xFE, 0x80, 0x00)
        val result = convertToShortBuffer(data, data.size, 16, bigEndian = true)
        assertContentEquals(shortArrayOf(0x1234, -2, -32768), shorts(result))
    }

    @Test
    fun twentyFourBitSamplesKeepTheTopSixteenBits() {
        val data = bytes(0x01, 0x02, 0x03, 0xAA, 0xBB, 0xCC, 0x00, 0x00, 0x80)
        val result = convertToShortBuffer(data, data.size, 24)
        assertContentEquals(shortArrayOf(0x0302, 0xCCBB.toShort(), -32768), shorts(result))
    }

    @Test
    fun thirtyTwoBitIntegerSamplesKeepTheTopSixteenBits() {
        val data = bytes(0x01, 0x02, 0x03, 0x04, 0xFF, 0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x00, 0x80)
        val result = convertToShortBuffer(data, data.size, 32)
        assertContentEquals(shortArrayOf(0x0403, 0x7FFF, -32768), shorts(result))
    }

    @Test
    fun thirtyTwoBitFloatSamplesAreScaledToSixteenBits() {
        val data = floatBytes(0f, 1f, -1f, 0.5f)
        val result = convertToShortBuffer(data, data.size, 32, isFloat = true)
        assertContentEquals(shortArrayOf(0, 32767, -32767, 16383), shorts(result))
    }

    @Test
    fun thirtyTwoBitFloatSamplesAreClampedToPlusMinusOne() {
        val data = floatBytes(2f, -3f, 1000f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val result = convertToShortBuffer(data, data.size, 32, isFloat = true)
        assertContentEquals(shortArrayOf(32767, -32767, 32767, 32767, -32767), shorts(result))
    }

    @Test
    fun resultIsFlippedForReading() {
        val data = bytes(1, 0, 2, 0, 3, 0)
        val result = convertToShortBuffer(data, data.size, 16)
        assertEquals(0, result.position())
        assertEquals(3, result.limit())
        assertEquals(3, result.remaining())
    }

    @Test
    fun onlyTheGivenNumberOfBytesIsConverted() {
        val data = bytes(1, 0, 2, 0, 3, 0)
        val result = convertToShortBuffer(data, 4, 16)
        assertContentEquals(shortArrayOf(1, 2), shorts(result))
    }

    @Test
    fun aTrailingPartialSampleIsDropped() {
        val data = bytes(1, 0, 2, 0, 3)
        val result = convertToShortBuffer(data, data.size, 16)
        assertContentEquals(shortArrayOf(1, 2), shorts(result))
    }

    @Test
    fun emptyInputGivesAnEmptyBuffer() {
        val result = convertToShortBuffer(ByteArray(0), 0, 16)
        assertEquals(0, result.remaining())
    }
}
