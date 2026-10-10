package dev.dertyp.synara.player.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiffHeaderTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun be16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun be32(v: Long): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun commPayload(channels: Int, frames: Long, bits: Int, rate: ByteArray, compression: String? = null): ByteArray {
        val base = be16(channels) + be32(frames) + be16(bits) + rate
        return if (compression == null) base else base + ascii(compression) + byteArrayOf(0, 0)
    }

    private fun rate44100(): ByteArray = bytes(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)

    private fun parse(payload: ByteArray, isAifc: Boolean): AiffHeader.Comm? =
        AiffHeader.parseComm(payload, payload.size, isAifc)

    private fun comm(compression: AiffHeader.Compression, bits: Int = 16, channels: Int = 2, sampleRate: Int = 44100) =
        AiffHeader.Comm(channels, 1000, bits, sampleRate, compression)

    @Test
    fun decodeExtendedReads44100() {
        assertEquals(44100, AiffHeader.decodeExtended(bytes(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun decodeExtendedReads48000() {
        assertEquals(48000, AiffHeader.decodeExtended(bytes(0x40, 0x0E, 0xBB, 0x80, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun decodeExtendedReads96000() {
        assertEquals(96000, AiffHeader.decodeExtended(bytes(0x40, 0x0F, 0xBB, 0x80, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun decodeExtendedReads22050() {
        assertEquals(22050, AiffHeader.decodeExtended(bytes(0x40, 0x0D, 0xAC, 0x44, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun decodeExtendedReads8000() {
        assertEquals(8000, AiffHeader.decodeExtended(bytes(0x40, 0x0B, 0xFA, 0x00, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun decodeExtendedHonoursTheOffset() {
        val data = bytes(0x11, 0x22, 0x33) + rate44100()
        assertEquals(44100, AiffHeader.decodeExtended(data, 3))
    }

    @Test
    fun decodeExtendedOfZeroIsZero() {
        assertEquals(0, AiffHeader.decodeExtended(ByteArray(10), 0))
    }

    @Test
    fun decodeExtendedOfInfinityAndNanIsZero() {
        assertEquals(0, AiffHeader.decodeExtended(bytes(0x7F, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0), 0))
        assertEquals(0, AiffHeader.decodeExtended(bytes(0x7F, 0xFF, 0xC0, 0, 0, 0, 0, 0, 0, 1), 0))
    }

    @Test
    fun decodeExtendedAppliesTheSignBit() {
        assertEquals(-44100, AiffHeader.decodeExtended(bytes(0xC0, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0), 0))
    }

    @Test
    fun parseCommRejectsPayloadsShorterThanEighteenBytes() {
        assertNull(AiffHeader.parseComm(ByteArray(17), 17, isAifc = false))
        assertNull(AiffHeader.parseComm(ByteArray(0), 0, isAifc = true))
    }

    @Test
    fun parseCommRejectsAnArrayShorterThanTheDeclaredSize() {
        assertNull(AiffHeader.parseComm(ByteArray(18), 20, isAifc = false))
    }

    @Test
    fun parseCommReadsTheCommonFields() {
        val result = assertNotNull(parse(commPayload(2, 4_000_000_000L, 24, rate44100()), isAifc = false))
        assertEquals(2, result.channelCount)
        assertEquals(4_000_000_000L, result.sampleFrames)
        assertEquals(24, result.sampleSizeBits)
        assertEquals(44100, result.sampleRate)
        assertEquals(3, result.bytesPerSample)
        assertEquals(6, result.bytesPerFrame)
        assertEquals(6L * 44100, result.bytesPerSecond)
    }

    @Test
    fun parseCommOfAiffHasCompressionNone() {
        val result = assertNotNull(parse(commPayload(2, 100, 16, rate44100(), compression = "sowt"), isAifc = false))
        assertEquals(AiffHeader.Compression.NONE, result.compression)
    }

    @Test
    fun parseCommOfAifcMapsEveryKnownCompressionId() {
        val expected = mapOf(
            "NONE" to AiffHeader.Compression.NONE,
            "sowt" to AiffHeader.Compression.SOWT,
            "twos" to AiffHeader.Compression.TWOS,
            "in24" to AiffHeader.Compression.IN24,
            "in32" to AiffHeader.Compression.IN32,
            "fl32" to AiffHeader.Compression.FL32,
            "FL32" to AiffHeader.Compression.FL32_UPPER,
            "raw " to AiffHeader.Compression.RAW,
        )
        for ((id, compression) in expected) {
            val result = assertNotNull(parse(commPayload(2, 100, 16, rate44100(), compression = id), isAifc = true), id)
            assertEquals(compression, result.compression, id)
        }
    }

    @Test
    fun parseCommOfAifcRejectsAnUnknownCompressionId() {
        assertNull(parse(commPayload(2, 100, 16, rate44100(), compression = "ima4"), isAifc = true))
    }

    @Test
    fun compressionFromIdRejectsUnknownIds() {
        assertNull(AiffHeader.Compression.fromId("ima4"))
        assertEquals(AiffHeader.Compression.SOWT, AiffHeader.Compression.fromId("sowt"))
    }

    @Test
    fun durationUsFollowsFramesAndRate() {
        assertEquals(1_000_000L, comm(AiffHeader.Compression.NONE).copy(sampleFrames = 44100).durationUs)
        assertEquals(0L, comm(AiffHeader.Compression.NONE, sampleRate = 0).durationUs)
    }

    @Test
    fun floatCompressionNeedsThirtyTwoBits() {
        assertTrue(comm(AiffHeader.Compression.FL32, bits = 32).isSupported)
        assertTrue(comm(AiffHeader.Compression.FL32_UPPER, bits = 32).isSupported)
        assertFalse(comm(AiffHeader.Compression.FL32, bits = 16).isSupported)
        assertFalse(comm(AiffHeader.Compression.FL32_UPPER, bits = 24).isSupported)
    }

    @Test
    fun sowtCompressionNeedsSixteenBits() {
        assertTrue(comm(AiffHeader.Compression.SOWT, bits = 16).isSupported)
        assertFalse(comm(AiffHeader.Compression.SOWT, bits = 24).isSupported)
        assertFalse(comm(AiffHeader.Compression.SOWT, bits = 8).isSupported)
    }

    @Test
    fun integerCompressionsAcceptOneToThirtyTwoBits() {
        for (compression in listOf(AiffHeader.Compression.NONE, AiffHeader.Compression.TWOS, AiffHeader.Compression.IN24, AiffHeader.Compression.IN32, AiffHeader.Compression.RAW)) {
            assertTrue(comm(compression, bits = 1).isSupported, compression.name)
            assertTrue(comm(compression, bits = 24).isSupported, compression.name)
            assertTrue(comm(compression, bits = 32).isSupported, compression.name)
            assertFalse(comm(compression, bits = 0).isSupported, compression.name)
            assertFalse(comm(compression, bits = 33).isSupported, compression.name)
        }
    }

    @Test
    fun isSupportedNeedsOneToEightChannelsAndAPositiveRate() {
        assertFalse(comm(AiffHeader.Compression.NONE, channels = 0).isSupported)
        assertFalse(comm(AiffHeader.Compression.NONE, channels = 9).isSupported)
        assertTrue(comm(AiffHeader.Compression.NONE, channels = 8).isSupported)
        assertFalse(comm(AiffHeader.Compression.NONE, sampleRate = 0).isSupported)
    }

    @Test
    fun toLittleEndianSwapsSixteenBitSamples() {
        val buffer = bytes(1, 2, 3, 4)
        AiffHeader.toLittleEndian(buffer, 4, 2, AiffHeader.Compression.TWOS)
        assertContentEquals(bytes(2, 1, 4, 3), buffer)
    }

    @Test
    fun toLittleEndianReversesTwentyFourBitSamples() {
        val buffer = bytes(1, 2, 3, 4, 5, 6)
        AiffHeader.toLittleEndian(buffer, 6, 3, AiffHeader.Compression.IN24)
        assertContentEquals(bytes(3, 2, 1, 6, 5, 4), buffer)
    }

    @Test
    fun toLittleEndianReversesThirtyTwoBitSamples() {
        val buffer = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        AiffHeader.toLittleEndian(buffer, 8, 4, AiffHeader.Compression.IN32)
        assertContentEquals(bytes(4, 3, 2, 1, 8, 7, 6, 5), buffer)
    }

    @Test
    fun toLittleEndianReversesBigEndianFloatSamples() {
        val buffer = bytes(0x3F, 0x80, 0, 0)
        AiffHeader.toLittleEndian(buffer, 4, 4, AiffHeader.Compression.FL32)
        assertContentEquals(bytes(0, 0, 0x80, 0x3F), buffer)
    }

    @Test
    fun toLittleEndianLeavesSowtUntouched() {
        val buffer = bytes(1, 2, 3, 4)
        AiffHeader.toLittleEndian(buffer, 4, 2, AiffHeader.Compression.SOWT)
        assertContentEquals(bytes(1, 2, 3, 4), buffer)
    }

    @Test
    fun toLittleEndianAddsOffsetToSignedEightBitSamples() {
        val buffer = bytes(0, 0x80, 0x7F, 0xFF)
        AiffHeader.toLittleEndian(buffer, 4, 1, AiffHeader.Compression.NONE)
        assertContentEquals(bytes(0x80, 0x00, 0xFF, 0x7F), buffer)
    }

    @Test
    fun toLittleEndianLeavesRawEightBitSamplesUntouched() {
        val buffer = bytes(0, 0x80, 0x7F, 0xFF)
        AiffHeader.toLittleEndian(buffer, 4, 1, AiffHeader.Compression.RAW)
        assertContentEquals(bytes(0, 0x80, 0x7F, 0xFF), buffer)
    }

    @Test
    fun toLittleEndianLeavesATrailingPartialSampleUntouched() {
        val sixteen = bytes(1, 2, 3, 4, 5)
        AiffHeader.toLittleEndian(sixteen, 5, 2, AiffHeader.Compression.TWOS)
        assertContentEquals(bytes(2, 1, 4, 3, 5), sixteen)

        val twentyFour = bytes(1, 2, 3, 4, 5)
        AiffHeader.toLittleEndian(twentyFour, 5, 3, AiffHeader.Compression.IN24)
        assertContentEquals(bytes(3, 2, 1, 4, 5), twentyFour)

        val thirtyTwo = bytes(1, 2, 3, 4, 5, 6)
        AiffHeader.toLittleEndian(thirtyTwo, 6, 4, AiffHeader.Compression.IN32)
        assertContentEquals(bytes(4, 3, 2, 1, 5, 6), thirtyTwo)
    }

    @Test
    fun toLittleEndianOnlyTouchesTheGivenLength() {
        val buffer = bytes(1, 2, 3, 4, 5, 6)
        AiffHeader.toLittleEndian(buffer, 2, 2, AiffHeader.Compression.TWOS)
        assertContentEquals(bytes(2, 1, 3, 4, 5, 6), buffer)
    }

    @Test
    fun isAiffRecognisesFormAiffAndAifc() {
        assertTrue(AiffHeader.isAiff(ascii("FORM") + be32(100) + ascii("AIFF")))
        assertTrue(AiffHeader.isAiff(ascii("FORM") + be32(100) + ascii("AIFC")))
    }

    @Test
    fun isAiffRejectsOtherForms() {
        assertFalse(AiffHeader.isAiff(ascii("FORM") + be32(100) + ascii("8SVX")))
        assertFalse(AiffHeader.isAiff(ascii("RIFF") + be32(100) + ascii("AIFF")))
    }

    @Test
    fun isAiffHonoursTheOffset() {
        val data = bytes(9, 9, 9) + ascii("FORM") + be32(100) + ascii("AIFF")
        assertTrue(AiffHeader.isAiff(data, 3))
        assertFalse(AiffHeader.isAiff(data, 0))
    }

    @Test
    fun isAiffRejectsHeadersShorterThanTwelveBytes() {
        assertFalse(AiffHeader.isAiff(ascii("FORM") + be32(100) + ascii("AIF")))
        assertFalse(AiffHeader.isAiff(ByteArray(0)))
        assertFalse(AiffHeader.isAiff(ascii("FORM") + be32(100) + ascii("AIFF"), 1))
    }
}
