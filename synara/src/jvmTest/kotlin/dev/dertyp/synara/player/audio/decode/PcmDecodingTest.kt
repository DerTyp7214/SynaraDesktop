package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.PcmHeader
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PcmDecodingTest {
    private class TestSource(private val opener: (Long) -> InputStream?) : AudioSource {
        val opens = mutableListOf<Long>()
        override val cacheKey: String = "test"
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = 0L

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            return opener(offset)
        }
    }

    private class ClosingStream(inner: InputStream, private val closed: CompletableDeferred<Unit>) : FilterInputStream(inner) {
        override fun close() {
            closed.complete(Unit)
            super.close()
        }
    }

    private class FailingStream(private val head: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int = throw IOException("source failed")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= head.size) throw IOException("source failed")
            val count = minOf(len, head.size - position)
            System.arraycopy(head, position, b, off, count)
            position += count
            return count
        }
    }

    private class EndlessStream(private val closed: CompletableDeferred<Unit>) : InputStream() {
        override fun read(): Int = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int = len

        override fun close() {
            closed.complete(Unit)
        }
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun extended80(rate: Int): ByteArray {
        val exponent = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - exponent)
        val biased = 16383 + exponent
        val out = ByteArray(10)
        out[0] = (biased shr 8).toByte()
        out[1] = biased.toByte()
        for (i in 0 until 8) out[2 + i] = (mantissa ushr (56 - 8 * i)).toByte()
        return out
    }

    private fun wav(channels: Int, rate: Int, bits: Int, data: ByteArray, format: Int = 1): ByteArray {
        val blockAlign = channels * bits / 8
        val fmt = le16(format) + le16(channels) + le32(rate) + le32(rate * blockAlign) + le16(blockAlign) + le16(bits)
        return ascii("RIFF") + le32(36 + data.size) + ascii("WAVE") + ascii("fmt ") + le32(16) + fmt +
            ascii("data") + le32(data.size) + data
    }

    private fun aiff(channels: Int, rate: Int, bits: Int, data: ByteArray): ByteArray {
        val bytesPerFrame = channels * ((bits + 7) / 8)
        val comm = be16(channels) + be32(data.size / bytesPerFrame) + be16(bits) + extended80(rate)
        val ssnd = be32(0) + be32(0) + data
        return ascii("FORM") + be32(4 + 8 + comm.size + 8 + ssnd.size) + ascii("AIFF") +
            ascii("COMM") + be32(comm.size) + comm + ascii("SSND") + be32(ssnd.size) + ssnd
    }

    private fun pcm16Le(samples: List<Int>) = samples.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    private fun pcm16Be(samples: List<Int>) = samples.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()

    private fun pcm24Le(values: List<Int>) = values.flatMap { listOf(it.toByte(), (it shr 8).toByte(), (it shr 16).toByte()) }.toByteArray()

    private fun pcm24Be(values: List<Int>) = values.flatMap { listOf((it shr 16).toByte(), (it shr 8).toByte(), it.toByte()) }.toByteArray()

    private fun pcm32Le(values: List<Int>) =
        values.flatMap { listOf(it.toByte(), (it shr 8).toByte(), (it shr 16).toByte(), (it shr 24).toByte()) }.toByteArray()

    private fun bytesSource(bytes: ByteArray) = TestSource { offset ->
        if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    private fun headerOf(bytes: ByteArray): PcmHeader =
        assertNotNull(runBlocking { PcmHeader.parseFromStream(ByteArrayInputStream(bytes), 0L) { null } })

    private fun manualHeader(
        sampleRate: Int = 8000,
        channels: Int = 1,
        bits: Int = 16,
        dataStart: Long = 0,
        dataSize: Long = 0
    ) = PcmHeader(sampleRate, channels, bits, isFloat = false, aiffCompression = null, dataStart = dataStart, dataSize = dataSize)

    private fun open(source: AudioSource, header: PcmHeader, positionMs: Long = 0): DecodedStream? =
        runBlocking { PcmDecoding.open(logger, source, header, positionMs, scope) }

    private fun DecodedStream.samples(): ShortArray {
        val buffers = runBlocking { withTimeout(10.seconds) { pcmFlow.toList() } }
        val out = mutableListOf<Short>()
        for (buffer in buffers) while (buffer.hasRemaining()) out.add(buffer.get())
        return out.toShortArray()
    }

    private fun decodeBytes(bytes: ByteArray): ShortArray {
        val stream = assertNotNull(open(bytesSource(bytes), headerOf(bytes)))
        return stream.samples()
    }

    @Test
    fun decodes8BitUnsignedWav() {
        val data = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00, 0x81.toByte())
        assertContentEquals(shortArrayOf(0, 32512, -32768, 256), decodeBytes(wav(1, 8000, 8, data)))
    }

    @Test
    fun decodes16BitLittleEndianWav() {
        val samples = listOf(0, 1, -1, 32767, -32768, 1000)
        assertContentEquals(samples.map { it.toShort() }.toShortArray(), decodeBytes(wav(1, 8000, 16, pcm16Le(samples))))
    }

    @Test
    fun decodes24BitWavToTheTopSixteenBits() {
        val values = listOf(0x123456, -0x123456, 0, 0x7FFFFF, -0x800000)
        val expected = values.map { (it shr 8).toShort() }.toShortArray()
        assertContentEquals(expected, decodeBytes(wav(1, 8000, 24, pcm24Le(values))))
    }

    @Test
    fun decodes32BitIntegerWavToTheTopSixteenBits() {
        val values = listOf(0x12345678, -0x12345678, Int.MIN_VALUE, Int.MAX_VALUE, 0)
        val expected = values.map { (it shr 16).toShort() }.toShortArray()
        assertContentEquals(expected, decodeBytes(wav(1, 8000, 32, pcm32Le(values))))
    }

    @Test
    fun decodes32BitFloatWavAndClamps() {
        val floats = listOf(0f, 0.5f, -0.5f, 1f, -1f, 2f, -3f)
        val data = pcm32Le(floats.map { it.toRawBits() })
        val expected = shortArrayOf(0, 16383, -16383, 32767, -32767, 32767, -32767)
        assertContentEquals(expected, decodeBytes(wav(1, 8000, 32, data, format = 3)))
    }

    @Test
    fun bigEndianAiffDecodesLikeLittleEndianWav() {
        val samples = listOf(0, 1, -1, 32767, -32768, 1000, -1234, 4321)
        val fromWav = decodeBytes(wav(2, 8000, 16, pcm16Le(samples)))
        val fromAiff = decodeBytes(aiff(2, 8000, 16, pcm16Be(samples)))
        assertContentEquals(fromWav, fromAiff)
        assertContentEquals(samples.map { it.toShort() }.toShortArray(), fromAiff)
    }

    @Test
    fun bigEndianAiff24BitDecodesToTheTopSixteenBits() {
        val values = listOf(0x123456, -0x123456, 0x7FFFFF, -0x800000)
        val expected = values.map { (it shr 8).toShort() }.toShortArray()
        assertContentEquals(expected, decodeBytes(aiff(1, 44100, 24, pcm24Be(values))))
    }

    @Test
    fun twelveBitAiffDecodesLikeSixteenBit() {
        val samples = listOf(0, 1, -1, 2047, -2048, 1000, -1234)
        val leftJustified = samples.map { it shl 4 }
        val bytes = aiff(1, 8000, 12, pcm16Be(leftJustified))
        val stream = assertNotNull(open(bytesSource(bytes), headerOf(bytes)))

        assertEquals(12, stream.bitsPerSample)
        assertContentEquals(leftJustified.map { it.toShort() }.toShortArray(), stream.samples())
        assertContentEquals(decodeBytes(aiff(1, 8000, 16, pcm16Be(leftJustified))), decodeBytes(bytes))
    }

    @Test
    fun twentyBitAiffDecodesToTheTopSixteenBits() {
        val values = listOf(0x12345, -0x12345, 0, 0x7FFFF, -0x80000)
        val bytes = aiff(1, 44100, 20, pcm24Be(values.map { it shl 4 }))
        val stream = assertNotNull(open(bytesSource(bytes), headerOf(bytes)))

        assertEquals(20, stream.bitsPerSample)
        assertContentEquals(values.map { (it shr 4).toShort() }.toShortArray(), stream.samples())
    }

    @Test
    fun aiff8BitIsSigned() {
        val data = byteArrayOf(0x10, 0xF0.toByte(), 0x00, 0x7F, 0x80.toByte())
        val expected = data.map { (it.toInt() shl 8).toShort() }.toShortArray()
        assertContentEquals(expected, decodeBytes(aiff(1, 8000, 8, data)))
    }

    @Test
    fun decodedStreamCarriesTheHeaderFormat() {
        val bytes = wav(2, 22050, 16, pcm16Le(listOf(1, 2, 3, 4)))
        val stream = assertNotNull(open(bytesSource(bytes), headerOf(bytes)))
        assertEquals(22050, stream.sampleRate)
        assertEquals(16, stream.bitsPerSample)
        assertEquals(2, stream.channels)
        assertEquals(0L, stream.startMs)
        assertNull(stream.durationMs)
        assertNull(stream.bitRate)
        stream.samples()
    }

    @Test
    fun decodedStreamCarriesTheDurationOfTheDeclaredDataSize() {
        val wavBytes = wav(2, 8000, 16, ByteArray(8000 * 4 * 3 / 2))
        val fromWav = assertNotNull(open(bytesSource(wavBytes), headerOf(wavBytes)))
        assertEquals(1500L, fromWav.durationMs)
        fromWav.samples()

        val aiffBytes = aiff(1, 8000, 24, ByteArray(8000 * 3 * 2))
        val fromAiff = assertNotNull(open(bytesSource(aiffBytes), headerOf(aiffBytes)))
        assertEquals(2000L, fromAiff.durationMs)
        fromAiff.samples()
    }

    @Test
    fun decodedStreamOpenedAtAPositionCarriesTheWholeDuration() {
        val bytes = wav(1, 8000, 16, ByteArray(8000 * 2 * 2))
        val stream = assertNotNull(open(bytesSource(bytes), headerOf(bytes), positionMs = 1500))
        assertEquals(1500L, stream.startMs)
        assertEquals(2000L, stream.durationMs)
        assertEquals(4000, stream.samples().size)
    }

    @Test
    fun decodedStreamWithoutADeclaredDataSizeCarriesNoDuration() {
        val stream = assertNotNull(open(bytesSource(pcm16Le(List(100) { it })), manualHeader(dataSize = 0)))
        assertNull(stream.durationMs)
        stream.samples()
    }

    @Test
    fun startsAtDataStartWithoutSeek() {
        val bytes = wav(1, 8000, 16, pcm16Le(List(100) { it }))
        val source = bytesSource(bytes)
        val stream = assertNotNull(open(source, headerOf(bytes)))
        assertEquals(listOf(44L), source.opens)
        assertEquals(100, stream.samples().size)
    }

    @Test
    fun seekOpensAtTheFrameOffset() {
        val frames = 500
        val samples = (0 until frames).flatMap { listOf(2 * it, 2 * it + 1) }
        val bytes = wav(2, 8000, 16, pcm16Le(samples))
        val header = headerOf(bytes)
        val source = bytesSource(bytes)

        val stream = assertNotNull(open(source, header, positionMs = 25))

        assertEquals(listOf(header.dataStart + 200 * 4), source.opens)
        assertEquals(25L, stream.startMs)
        val decoded = stream.samples()
        assertEquals((frames - 200) * 2, decoded.size)
        assertEquals(400.toShort(), decoded[0])
        assertEquals(401.toShort(), decoded[1])
        assertEquals((2 * (frames - 1) + 1).toShort(), decoded.last())
    }

    @Test
    fun seekBeyondTheEndIsClampedToTheLastFrame() {
        val bytes = wav(1, 8000, 16, pcm16Le(List(1000) { it }))
        val header = headerOf(bytes)
        val source = bytesSource(bytes)

        val stream = assertNotNull(open(source, header, positionMs = 1_000_000))

        assertEquals(listOf(header.dataStart + 999 * 2), source.opens)
        assertContentEquals(shortArrayOf(999), stream.samples())
    }

    @Test
    fun seekWithUnknownDataSizeIsNotClamped() {
        val header = manualHeader(dataSize = 0)
        val source = bytesSource(pcm16Le(List(100) { it }))

        open(source, header, positionMs = 1)

        assertEquals(listOf(8L * 2), source.opens)
    }

    @Test
    fun trailingPartialFrameIsDropped() {
        val data = pcm16Le(listOf(1, 2, 3, 4)) + byteArrayOf(9, 9, 9)
        for (dataSize in listOf(0L, data.size.toLong())) {
            val header = manualHeader(channels = 2, dataSize = dataSize)
            val stream = assertNotNull(open(bytesSource(data), header))
            assertContentEquals(shortArrayOf(1, 2, 3, 4), stream.samples(), "dataSize $dataSize")
        }
    }

    @Test
    fun declaredDataSizeLimitsWhatIsRead() {
        val header = manualHeader(dataSize = 4)
        val stream = assertNotNull(open(bytesSource(pcm16Le(listOf(1, 2, 3, 4, 5))), header))
        assertContentEquals(shortArrayOf(1, 2), stream.samples())
    }

    @Test
    fun dataLargerThanOneReadChunkKeepsItsOrder() {
        val samples = List(20_000) { it % 30_000 }
        val header = manualHeader()
        val stream = assertNotNull(open(bytesSource(pcm16Le(samples)), header))
        assertContentEquals(samples.map { it.toShort() }.toShortArray(), stream.samples())
    }

    @Test
    fun zeroBytesPerFrameGivesNullWithoutOpening() {
        val source = bytesSource(ByteArray(16))
        assertNull(open(source, manualHeader(channels = 0)))
        assertNull(open(source, manualHeader(bits = 0)))
        assertEquals(emptyList<Long>(), source.opens)
    }

    @Test
    fun zeroSampleRateGivesNullWithoutOpening() {
        val source = bytesSource(ByteArray(16))
        assertNull(open(source, manualHeader(sampleRate = 0)))
        assertEquals(emptyList<Long>(), source.opens)
    }

    @Test
    fun sourceThatCannotBeOpenedGivesNull() {
        val source = TestSource { null }
        assertNull(open(source, manualHeader()))
        assertEquals(listOf(0L), source.opens)
    }

    @Test
    fun readFailureIsLoggedOnceAndFailsTheFlow() {
        val source = TestSource { FailingStream(ByteArray(4)) }
        val stream = assertNotNull(open(source, manualHeader()))

        assertFailsWith<IOException> { stream.samples() }

        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun sourceStreamIsClosedWhenDecodingFinishes() {
        val closed = CompletableDeferred<Unit>()
        val bytes = pcm16Le(listOf(1, 2, 3))
        val source = TestSource { ClosingStream(ByteArrayInputStream(bytes), closed) }
        val stream = assertNotNull(open(source, manualHeader()))

        stream.samples()

        runBlocking { withTimeout(10.seconds) { closed.await() } }
    }

    @Test
    fun stoppingTheConsumerClosesTheSourceStreamWithoutLoggingAnError() {
        val closed = CompletableDeferred<Unit>()
        val source = TestSource { EndlessStream(closed) }
        val stream = assertNotNull(open(source, manualHeader()))

        runBlocking { withTimeout(10.seconds) { stream.pcmFlow.first() } }

        runBlocking { withTimeout(10.seconds) { closed.await() } }
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
    }
}
