package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ShortBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OggOpusDecodingTest {
    private open class BytesSource(protected val bytes: ByteArray, override val durationMsHint: Long? = null) : AudioSource {
        val opens = CopyOnWriteArrayList<Long>()
        override val cacheKey: String = "ogg-opus-decoding"
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            return stream(offset.toInt())
        }

        protected open fun stream(offset: Int): InputStream? =
            if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset, bytes.size - offset)
    }

    private class GatedSource(bytes: ByteArray, val gateAt: Int, durationMsHint: Long?) : BytesSource(bytes, durationMsHint) {
        val reached = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override fun stream(offset: Int): InputStream = object : InputStream() {
            private var position = offset

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (position >= bytes.size) return -1
                if (position >= gateAt) {
                    reached.countDown()
                    gate.await()
                }
                val limit = if (position < gateAt) gateAt else bytes.size
                val count = min(len, limit - position)
                System.arraycopy(bytes, position, b, off, count)
                position += count
                return count
            }
        }
    }

    private class BreakingSource(bytes: ByteArray, val failAt: Int, durationMsHint: Long?) : BytesSource(bytes, durationMsHint) {
        override fun stream(offset: Int): InputStream = object : InputStream() {
            private var position = offset

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (position >= failAt) throw IOException("connection reset")
                val count = min(len, failAt - position)
                System.arraycopy(bytes, position, b, off, count)
                position += count
                return count
            }
        }
    }

    private class UnrangedSource(bytes: ByteArray) : BytesSource(bytes) {
        override fun stream(offset: Int): InputStream? = if (offset > 0) null else super.stream(offset)
    }

    private class TailFailingSource(bytes: ByteArray) : BytesSource(bytes) {
        override fun stream(offset: Int): InputStream =
            if (offset > 0) throw IOException("ranged reads are not supported") else checkNotNull(super.stream(offset))
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())
    private val frames = 2 * 48000 + 123

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun open(source: AudioSource): DecodedStream = runBlocking {
        assertNotNull(AudioDecoders(logger).open(source, 0, scope))
    }

    private fun DecodedStream.drain(): List<ShortBuffer> = runBlocking {
        withTimeout(30.seconds) {
            val buffers = mutableListOf<ShortBuffer>()
            pcmFlow.collect { buffers += it }
            buffers
        }
    }

    private fun DecodedStream.drainUntilFailure(): Pair<List<ShortBuffer>, IOException> {
        val buffers = mutableListOf<ShortBuffer>()
        val failure = assertFailsWith<IOException> {
            runBlocking { withTimeout(30.seconds) { pcmFlow.collect { buffers += it } } }
        }
        return buffers to failure
    }

    private fun List<ShortBuffer>.frames(channels: Int): Int = sumOf { it.remaining() } / channels

    private fun verifyWarnings(count: Int) = verify(exactly = count) { logger.warning(any(), any(), any()) }

    @Test
    fun formatIsReportedAsSoonAsTheHeadIsReadWhileTheTagsAreStillLoading() {
        val fixture = OggOpusFixture.tone(frames, tagsPadding = 200_000)
        val gateAt = 8192
        assertTrue(gateAt in fixture.pageOffsets()[1] until fixture.audioStart)
        val source = GatedSource(fixture.bytes, gateAt, durationMsHint = 60_000)

        val stream = open(source)

        assertEquals(48000, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertEquals(16, stream.bitsPerSample)
        assertTrue(source.reached.await(10, TimeUnit.SECONDS))
        assertEquals(1L, source.gate.count)

        source.gate.countDown()

        assertEquals(frames, stream.drain().frames(2))
        verifyWarnings(0)
    }

    @Test
    fun pagesReceivedBeforeTheStreamBreaksAreDecodedBeforeTheFailure() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)
        val pages = fixture.pageOffsets()
        val failAt = pages[pages.size / 2] + 10
        val expectedPackets = fixture.audioPacketsBefore(failAt)
        assertTrue(expectedPackets in 1 until fixture.packets)
        val source = BreakingSource(fixture.bytes, failAt, durationMsHint = 60_000)

        val stream = open(source)
        val (buffers, failure) = stream.drainUntilFailure()

        assertEquals("connection reset", failure.message)
        assertEquals(expectedPackets * OggOpusFixture.PACKET_FRAMES - OggOpusFixture.PRE_SKIP, buffers.frames(2))
        verifyWarnings(0)
    }

    @Test
    fun aStreamThatBreaksRightAfterItsLastPageIsNotTrimmed() {
        val fixture = OggOpusFixture.tone(frames)
        assertTrue(fixture.decodedFrames > frames)
        val source = BreakingSource(fixture.bytes, fixture.bytes.size, durationMsHint = 60_000)

        val stream = open(source)
        val (buffers, failure) = stream.drainUntilFailure()

        assertEquals("connection reset", failure.message)
        assertEquals(fixture.decodedFrames, buffers.frames(2))
        verifyWarnings(0)
    }

    @Test
    fun aStreamThatEndsInsideAPageIsAFailureNotAnEnd() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)
        val pages = fixture.pageOffsets()
        val cut = pages[pages.size / 2] + 10
        val expectedPackets = fixture.audioPacketsBefore(cut)
        assertTrue(expectedPackets in 1 until fixture.packets)
        val source = BytesSource(fixture.bytes.copyOf(cut), durationMsHint = 60_000)

        val stream = open(source)
        val (buffers, _) = stream.drainUntilFailure()

        assertEquals(expectedPackets * OggOpusFixture.PACKET_FRAMES - OggOpusFixture.PRE_SKIP, buffers.frames(2))
        verifyWarnings(0)
    }

    @Test
    fun aStreamThatEndsInsideItsLastPageIsAFailureNotAnEnd() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)
        val source = BytesSource(fixture.bytes.copyOf(fixture.bytes.size - 1), durationMsHint = 60_000)

        val stream = open(source)
        val (buffers, _) = stream.drainUntilFailure()

        assertEquals(fixture.framesBeforeLastPage, buffers.frames(2))
        verifyWarnings(0)
    }

    @Test
    fun bytesAfterTheLastPageOfACompleteStreamAreNotAFailure() {
        val fixture = OggOpusFixture.tone(frames)
        val source = BytesSource(fixture.bytes + ByteArray(100), durationMsHint = 60_000)

        val stream = open(source)

        assertEquals(frames, stream.drain().frames(2))
        verifyWarnings(0)
    }

    @Test
    fun aStreamThatBreaksBeforeItsHeadDoesNotOpen() {
        val fixture = OggOpusFixture.tone(frames)
        val source = BreakingSource(fixture.bytes, 20, durationMsHint = 60_000)

        assertNull(runBlocking { AudioDecoders(logger).open(source, 0, scope) })
    }

    @Test
    fun durationIsReadFromTheLastPageOfTheStreamAtOpen() {
        val source = BytesSource(longFixture.bytes)

        val stream = open(source)

        assertEquals(LONG_FRAMES * 1000L / 48000, stream.durationMs)
        assertEquals(listOf(0L, (longFixture.bytes.size - OggTail.TAIL_BYTES).toLong(), 0L), source.opens)
        assertEquals(LONG_FRAMES, stream.drain().frames(2))
        verifyWarnings(0)
    }

    @Test
    fun durationOfAStreamShorterThanTheTailIsReadFromItsStart() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)
        assertTrue(fixture.bytes.size < OggTail.TAIL_BYTES)
        val source = BytesSource(fixture.bytes)

        val stream = open(source)

        assertEquals(frames * 1000L / 48000, stream.durationMs)
        assertEquals(listOf(0L, 0L, 0L), source.opens)
    }

    @Test
    fun durationIsNotReadWhenTheSourceAlreadyKnowsIt() {
        val source = BytesSource(longFixture.bytes, durationMsHint = 60_000)

        val stream = open(source)

        assertNull(stream.durationMs)
        assertEquals(listOf(0L, 0L), source.opens)
    }

    @Test
    fun durationStaysUnknownWhenTheSourceCannotBeReadAtAnOffset() {
        val source = UnrangedSource(longFixture.bytes)

        val stream = open(source)

        assertNull(stream.durationMs)
        assertEquals(listOf(0L, (longFixture.bytes.size - OggTail.TAIL_BYTES).toLong(), 0L), source.opens)
        assertEquals(LONG_FRAMES, stream.drain().frames(2))
        verifyWarnings(0)
    }

    @Test
    fun durationStaysUnknownWhenTheTailReadFails() {
        val source = TailFailingSource(longFixture.bytes)

        val stream = open(source)

        assertNull(stream.durationMs)
        assertEquals(listOf(0L, (longFixture.bytes.size - OggTail.TAIL_BYTES).toLong(), 0L), source.opens)
        assertEquals(LONG_FRAMES, stream.drain().frames(2))
        verifyWarnings(1)
    }

    @Test
    fun durationIsReadOncePerSourceAcrossReopens() {
        val source = BytesSource(longFixture.bytes)
        val decoders = AudioDecoders(logger)

        val first = runBlocking { assertNotNull(decoders.open(source, 0, scope)) }
        first.drain()
        val second = runBlocking { assertNotNull(decoders.open(source, 0, scope)) }
        second.drain()

        assertEquals(first.durationMs, second.durationMs)
        assertEquals(listOf(0L, (longFixture.bytes.size - OggTail.TAIL_BYTES).toLong(), 0L, 0L), source.opens)
    }

    private companion object {
        const val LONG_FRAMES = 10 * 48000 + 7
        val longFixture: OggOpusFixture by lazy { OggOpusFixture.tone(LONG_FRAMES, packetsPerPage = 8) }
    }
}
