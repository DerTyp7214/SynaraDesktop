package dev.dertyp.synara.player.audio

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmHeaderTest {
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun le32(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun le32(v: Int): ByteArray = le32(v.toLong())

    private fun be16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun be32(v: Long): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun be32(v: Int): ByteArray = be32(v.toLong())

    private fun pad(size: Int): ByteArray = if (size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun join(parts: Array<out ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun fmtPayload(format: Int, channels: Int, rate: Int, bits: Int): ByteArray =
        le16(format) + le16(channels) + le32(rate) + le32(rate * channels * bits / 8) + le16(channels * bits / 8) + le16(bits)

    private fun subFormat(format: Int): ByteArray =
        le16(format) + bytes(0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71)

    private fun extensiblePayload(channels: Int, rate: Int, bits: Int, subFormat: Int): ByteArray =
        fmtPayload(0xFFFE, channels, rate, bits) + le16(22) + le16(bits) + le32(3) + subFormat(subFormat)

    private fun riffChunk(id: String, payload: ByteArray): ByteArray =
        ascii(id) + le32(payload.size) + payload + pad(payload.size)

    private fun dataChunk(declaredSize: Long, payload: ByteArray): ByteArray = ascii("data") + le32(declaredSize) + payload

    private fun riff(vararg parts: ByteArray): ByteArray {
        val body = join(parts)
        return ascii("RIFF") + le32(4 + body.size) + ascii("WAVE") + body
    }

    private fun wavWith(format: Int, channels: Int, rate: Int, bits: Int): ByteArray =
        riff(riffChunk("fmt ", fmtPayload(format, channels, rate, bits)), dataChunk(100, ByteArray(100)))

    private fun minimalWav(dataSize: Int = 4000): ByteArray =
        riff(riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)), dataChunk(dataSize.toLong(), ByteArray(dataSize)))

    private fun parse(bytes: ByteArray, base: Long = 0): PcmHeader? =
        PcmHeader.parseFromStream(ByteArrayInputStream(bytes), base)

    private fun ext44100(): ByteArray = bytes(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)

    private fun aiffChunk(id: String, payload: ByteArray): ByteArray =
        ascii(id) + be32(payload.size) + payload + pad(payload.size)

    private fun comm(channels: Int, frames: Int, bits: Int, compression: String? = null): ByteArray {
        var payload = be16(channels) + be32(frames) + be16(bits) + ext44100()
        if (compression != null) payload += ascii(compression) + byteArrayOf(0, 0)
        return aiffChunk("COMM", payload)
    }

    private fun ssnd(offset: Int, data: ByteArray, declaredSize: Long = 8L + offset + data.size): ByteArray =
        ascii("SSND") + be32(declaredSize) + be32(offset) + be32(0) + ByteArray(offset) + data

    private fun form(type: String, vararg parts: ByteArray): ByteArray {
        val body = join(parts)
        return ascii("FORM") + be32(4 + body.size) + ascii(type) + body
    }

    @Test
    fun minimalWavStartsDataAtByte44() {
        val header = assertNotNull(parse(minimalWav()))
        assertEquals(44100, header.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(16, header.bitsPerSample)
        assertFalse(header.isFloat)
        assertNull(header.aiffCompression)
        assertEquals(44L, header.dataStart)
        assertEquals(4000L, header.dataSize)
        assertEquals(2, header.bytesPerSample)
        assertEquals(4, header.bytesPerFrame)
    }

    @Test
    fun streamOffsetOfMagicIsAddedToWavDataStart() {
        val header = assertNotNull(parse(minimalWav(), base = 1000))
        assertEquals(1044L, header.dataStart)
    }

    @Test
    fun listChunkBeforeDataIsSkipped() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)),
            riffChunk("LIST", ByteArray(10)),
            dataChunk(400, ByteArray(400)),
        )
        val header = assertNotNull(parse(wav))
        assertEquals(62L, header.dataStart)
        assertEquals(400L, header.dataSize)
    }

    @Test
    fun listChunkBeforeFmtIsSkipped() {
        val wav = riff(
            riffChunk("LIST", ByteArray(12)),
            riffChunk("fmt ", fmtPayload(1, 1, 8000, 8)),
            dataChunk(100, ByteArray(100)),
        )
        val header = assertNotNull(parse(wav))
        assertEquals(8000, header.sampleRate)
        assertEquals(1, header.channels)
        assertEquals(8, header.bitsPerSample)
        assertEquals(12L + 20 + 24 + 8, header.dataStart)
    }

    @Test
    fun oddSizedChunkConsumesItsPadByte() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)),
            riffChunk("LIST", ByteArray(5)),
            dataChunk(400, ByteArray(400)),
        )
        val header = assertNotNull(parse(wav))
        assertEquals(58L, header.dataStart)
        assertEquals(400L, header.dataSize)
    }

    @Test
    fun oddSizedFmtChunkConsumesItsPadByte() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16) + byteArrayOf(0)),
            dataChunk(400, ByteArray(400)),
        )
        val header = assertNotNull(parse(wav))
        assertEquals(46L, header.dataStart)
        assertEquals(44100, header.sampleRate)
    }

    @Test
    fun fmtChunkLargerThan64BytesIsSkippedToItsEnd() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16) + ByteArray(64)),
            dataChunk(400, ByteArray(400)),
        )
        val header = assertNotNull(parse(wav))
        assertEquals(12L + 8 + 80 + 8, header.dataStart)
        assertEquals(400L, header.dataSize)
        assertEquals(44100, header.sampleRate)
    }

    @Test
    fun extensibleWithFloatSubFormatIsFloat() {
        val wav = riff(
            riffChunk("fmt ", extensiblePayload(2, 48000, 32, subFormat = 3)),
            dataChunk(800, ByteArray(800)),
        )
        val header = assertNotNull(parse(wav))
        assertTrue(header.isFloat)
        assertEquals(32, header.bitsPerSample)
        assertEquals(2, header.channels)
        assertEquals(48000, header.sampleRate)
        assertEquals(12L + 8 + 40 + 8, header.dataStart)
    }

    @Test
    fun extensibleWithPcmSubFormatIsNotFloat() {
        val wav = riff(
            riffChunk("fmt ", extensiblePayload(6, 48000, 24, subFormat = 1)),
            dataChunk(800, ByteArray(800)),
        )
        val header = assertNotNull(parse(wav))
        assertFalse(header.isFloat)
        assertEquals(24, header.bitsPerSample)
        assertEquals(6, header.channels)
    }

    @Test
    fun extensibleFmtWithoutSubFormatIsRejected() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(0xFFFE, 2, 44100, 16)),
            dataChunk(100, ByteArray(100)),
        )
        assertNull(parse(wav))
    }

    @Test
    fun fmtChunkShorterThan16BytesIsRejected() {
        val wav = riff(
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16).copyOf(14)),
            dataChunk(100, ByteArray(100)),
        )
        assertNull(parse(wav))
    }

    @Test
    fun floatWavWith32BitsIsFloat() {
        val header = assertNotNull(parse(wavWith(3, 2, 44100, 32)))
        assertTrue(header.isFloat)
        assertEquals(32, header.bitsPerSample)
    }

    @Test
    fun dataSizeZeroGivesUnknownSize() {
        val wav = riff(riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)), dataChunk(0, ByteArray(100)))
        val header = assertNotNull(parse(wav))
        assertEquals(0L, header.dataSize)
        assertEquals(44L, header.dataStart)
    }

    @Test
    fun dataSizeAllOnesGivesUnknownSize() {
        val wav = riff(riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)), dataChunk(0xFFFFFFFFL, ByteArray(100)))
        val header = assertNotNull(parse(wav))
        assertEquals(0L, header.dataSize)
    }

    @Test
    fun dataSizeAboveTwoGibIsReadUnsigned() {
        val wav = riff(riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)), dataChunk(0xFFFFFFFEL, ByteArray(100)))
        val header = assertNotNull(parse(wav))
        assertEquals(0xFFFFFFFEL, header.dataSize)
    }

    @Test
    fun dataChunkBeforeFmtIsRejected() {
        val wav = riff(
            dataChunk(100, ByteArray(100)),
            riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)),
        )
        assertNull(parse(wav))
    }

    @Test
    fun supportedBitDepthsAreAccepted() {
        for (bits in listOf(8, 16, 24, 32)) {
            val header = assertNotNull(parse(wavWith(1, 2, 44100, bits)), "bits $bits")
            assertEquals(bits, header.bitsPerSample)
        }
    }

    @Test
    fun unsupportedBitDepthIsRejected() {
        assertNull(parse(wavWith(1, 2, 44100, 12)))
        assertNull(parse(wavWith(1, 2, 44100, 20)))
        assertNull(parse(wavWith(1, 2, 44100, 64)))
    }

    @Test
    fun floatWithBitsOtherThan32IsRejected() {
        assertNull(parse(wavWith(3, 2, 44100, 64)))
        assertNull(parse(wavWith(3, 2, 44100, 16)))
    }

    @Test
    fun unsupportedFormatCodeIsRejected() {
        assertNull(parse(wavWith(0, 2, 44100, 16)))
        assertNull(parse(wavWith(2, 2, 44100, 16)))
        assertNull(parse(wavWith(0x55, 2, 44100, 16)))
    }

    @Test
    fun channelCountOutsideOneToEightIsRejected() {
        assertNull(parse(wavWith(1, 0, 44100, 16)))
        assertNull(parse(wavWith(1, 9, 44100, 16)))
        assertEquals(1, assertNotNull(parse(wavWith(1, 1, 44100, 16))).channels)
        assertEquals(8, assertNotNull(parse(wavWith(1, 8, 44100, 16))).channels)
    }

    @Test
    fun sampleRateZeroIsRejected() {
        assertNull(parse(wavWith(1, 2, 0, 16)))
    }

    @Test
    fun truncatedWavHeaderIsRejected() {
        val wav = minimalWav()
        for (cut in 0 until 44) assertNull(parse(wav.copyOf(cut)), "cut at $cut")
    }

    @Test
    fun wavWithoutDataChunkIsRejected() {
        val wav = riff(riffChunk("fmt ", fmtPayload(1, 2, 44100, 16)), riffChunk("LIST", ByteArray(10)))
        assertNull(parse(wav))
    }

    @Test
    fun streamWithoutRiffOrFormMagicIsRejected() {
        assertNull(parse(ascii("RIFX") + ByteArray(40)))
        assertNull(parse(ascii("RIFF") + le32(100) + ascii("AVI ") + ByteArray(40)))
        assertNull(parse(ByteArray(40)))
        assertNull(parse(ByteArray(5)))
    }

    @Test
    fun isWavRecognisesRiffWave() {
        assertTrue(PcmHeader.isWav(minimalWav()))
        assertFalse(PcmHeader.isWav(ascii("RIFF") + le32(100) + ascii("AVI ")))
        assertFalse(PcmHeader.isWav(ascii("RIFF") + le32(100) + ascii("WAV")))
        assertFalse(PcmHeader.isWav(ByteArray(0)))
    }

    @Test
    fun isWavHonoursTheOffset() {
        val shifted = ByteArray(7) + minimalWav()
        assertTrue(PcmHeader.isWav(shifted, 7))
        assertFalse(PcmHeader.isWav(shifted, 0))
        assertFalse(PcmHeader.isWav(minimalWav().copyOf(14), 7))
    }

    @Test
    fun aiffCommThenSsndGivesDataStartAndSize() {
        val aiff = form("AIFF", comm(2, 100, 16), ssnd(0, ByteArray(400)))
        val header = assertNotNull(parse(aiff))
        assertEquals(44100, header.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(16, header.bitsPerSample)
        assertFalse(header.isFloat)
        assertEquals(AiffHeader.Compression.NONE, header.aiffCompression)
        assertEquals(54L, header.dataStart)
        assertEquals(400L, header.dataSize)
    }

    @Test
    fun ssndOffsetAdvancesDataStartAndShrinksDataSize() {
        val aiff = form("AIFF", comm(2, 100, 16), ssnd(4, ByteArray(100)))
        val header = assertNotNull(parse(aiff))
        assertEquals(58L, header.dataStart)
        assertEquals(100L, header.dataSize)
    }

    @Test
    fun streamOffsetOfMagicIsAddedToAiffDataStart() {
        val aiff = form("AIFF", comm(2, 100, 16), ssnd(4, ByteArray(100)))
        val header = assertNotNull(parse(aiff, base = 500))
        assertEquals(558L, header.dataStart)
    }

    @Test
    fun ssndOffsetBeyondDeclaredSizeCoercesDataSizeToZero() {
        val aiff = form("AIFF", comm(2, 100, 16), ssnd(4, ByteArray(0), declaredSize = 8))
        val header = assertNotNull(parse(aiff))
        assertEquals(0L, header.dataSize)
        assertEquals(58L, header.dataStart)
    }

    @Test
    fun ssndSizeZeroAndAllOnesGiveUnknownSize() {
        val zero = assertNotNull(parse(form("AIFF", comm(2, 100, 16), ssnd(0, ByteArray(10), declaredSize = 0))))
        assertEquals(0L, zero.dataSize)
        val ones = assertNotNull(parse(form("AIFF", comm(2, 100, 16), ssnd(0, ByteArray(10), declaredSize = 0xFFFFFFFFL))))
        assertEquals(0L, ones.dataSize)
    }

    @Test
    fun ssndBeforeCommIsRejected() {
        val aiff = form("AIFF", ssnd(0, ByteArray(400)), comm(2, 100, 16))
        assertNull(parse(aiff))
    }

    @Test
    fun unknownAiffChunksAreSkippedWithTheirPadBytes() {
        val aiff = form(
            "AIFF",
            aiffChunk("FVER", ByteArray(5)),
            comm(2, 100, 16),
            aiffChunk("ANNO", ByteArray(3)),
            ssnd(0, ByteArray(40)),
        )
        val header = assertNotNull(parse(aiff))
        assertEquals(80L, header.dataStart)
        assertEquals(40L, header.dataSize)
    }

    @Test
    fun aifcSowtCompressionIsCarried() {
        val aiff = form("AIFC", comm(2, 100, 16, compression = "sowt"), ssnd(0, ByteArray(400)))
        val header = assertNotNull(parse(aiff))
        assertEquals(AiffHeader.Compression.SOWT, header.aiffCompression)
        assertEquals(16, header.bitsPerSample)
        assertEquals(60L, header.dataStart)
        assertEquals(400L, header.dataSize)
    }

    @Test
    fun aifcFloatCompressionIsFloat() {
        val aiff = form("AIFC", comm(2, 100, 32, compression = "fl32"), ssnd(0, ByteArray(400)))
        val header = assertNotNull(parse(aiff))
        assertTrue(header.isFloat)
        assertEquals(AiffHeader.Compression.FL32, header.aiffCompression)
        assertEquals(32, header.bitsPerSample)
    }

    @Test
    fun aifcWithUnsupportedCompressionIsRejected() {
        val aiff = form("AIFC", comm(2, 100, 16, compression = "ima4"), ssnd(0, ByteArray(400)))
        assertNull(parse(aiff))
    }

    @Test
    fun aiffWithUnsupportedCommParametersIsRejected() {
        assertNull(parse(form("AIFF", comm(0, 100, 16), ssnd(0, ByteArray(40)))))
        assertNull(parse(form("AIFF", comm(9, 100, 16), ssnd(0, ByteArray(40)))))
        assertNull(parse(form("AIFF", comm(2, 100, 0), ssnd(0, ByteArray(40)))))
        assertNull(parse(form("AIFF", comm(2, 100, 33), ssnd(0, ByteArray(40)))))
        assertNull(parse(form("AIFC", comm(2, 100, 24, compression = "sowt"), ssnd(0, ByteArray(40)))))
        assertNull(parse(form("AIFC", comm(2, 100, 16, compression = "fl32"), ssnd(0, ByteArray(40)))))
    }

    @Test
    fun truncatedAiffHeaderIsRejected() {
        val aiff = form("AIFF", comm(2, 100, 16), ssnd(0, ByteArray(400)))
        for (cut in 0 until 54) assertNull(parse(aiff.copyOf(cut)), "cut at $cut")
    }

    @Test
    fun encodeProducesTwentyNineBytes() {
        val header = PcmHeader(44100, 2, 16, false, null, 44, 4000)
        assertEquals(29, header.encode().size)
    }

    @Test
    fun encodeDecodeRoundTripsWithoutAiffCompression() {
        val header = PcmHeader(96000, 6, 24, false, null, 12_345_678_901L, 9_876_543_210L)
        assertEquals(header, PcmHeader.decode(header.encode()))
    }

    @Test
    fun encodeDecodeRoundTripsFloatWav() {
        val header = PcmHeader(48000, 2, 32, true, null, 44, 8000)
        assertEquals(header, PcmHeader.decode(header.encode()))
    }

    @Test
    fun encodeDecodeRoundTripsEveryAiffCompression() {
        for (compression in AiffHeader.Compression.entries) {
            val header = PcmHeader(44100, 2, 32, compression.isFloat, compression, 54, 123_456)
            assertEquals(header, PcmHeader.decode(header.encode()), compression.name)
        }
    }

    @Test
    fun decodeRejectsWrongSize() {
        val encoded = PcmHeader(44100, 2, 16, false, null, 44, 4000).encode()
        assertNull(PcmHeader.decode(encoded.copyOf(28)))
        assertNull(PcmHeader.decode(encoded + byteArrayOf(0)))
        assertNull(PcmHeader.decode(ByteArray(0)))
    }

    @Test
    fun decodeRejectsWrongMagic() {
        val encoded = PcmHeader(44100, 2, 16, false, null, 44, 4000).encode()
        encoded[0] = 'X'.code.toByte()
        assertNull(PcmHeader.decode(encoded))
    }

    @Test
    fun decodeRejectsWrongVersion() {
        val encoded = PcmHeader(44100, 2, 16, false, null, 44, 4000).encode()
        encoded[4] = 2
        assertNull(PcmHeader.decode(encoded))
    }

    @Test
    fun decodeRejectsOutOfRangeCompressionOrdinal() {
        val encoded = PcmHeader(44100, 2, 16, false, null, 44, 4000).encode()
        encoded[12] = (AiffHeader.Compression.entries.size + 1).toByte()
        assertNull(PcmHeader.decode(encoded))
    }

    @Test
    fun normalizeSwapsBigEndianAiffSamplesInPlace() {
        val header = PcmHeader(44100, 1, 16, false, AiffHeader.Compression.TWOS, 0, 0)
        val buffer = bytes(1, 2, 3, 4)
        header.normalize(buffer, 4)
        assertContentEquals(bytes(2, 1, 4, 3), buffer)
    }

    @Test
    fun normalizeLeavesWavSamplesUntouched() {
        val header = PcmHeader(44100, 1, 16, false, null, 0, 0)
        val buffer = bytes(1, 2, 3, 4)
        header.normalize(buffer, 4)
        assertContentEquals(bytes(1, 2, 3, 4), buffer)
    }
}
