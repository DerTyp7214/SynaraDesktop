package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.PcmAccumulator
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.nio.ShortBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DecoderPipelineTest {
    private class DecodeFailure : IOException()

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newSink() = PcmSink(Channel<ShortBuffer>(Channel.UNLIMITED))

    private fun shorts(start: Int, count: Int) = ShortArray(count) { (start + it).toShort() }

    private fun decoding(
        startMs: Long = 0,
        durationMs: Long? = null,
        bitRate: Long? = null,
        trim: (PcmFormat) -> Long = { 0L },
        decode: suspend (PcmSink) -> Unit
    ): DecodedStream = runBlocking {
        launchDecoding(logger, scope, "test", startMs, durationMs, bitRate, trim, decode = decode)
    }

    private fun DecodedStream.samples(): List<Short> {
        val buffers = runBlocking { withTimeout(10.seconds) { pcmFlow.toList() } }
        return buffers.flatMap { buffer -> List(buffer.remaining()) { buffer.get() } }
    }

    @Test
    fun sinkFixesTheFormatOnFirstAccept() {
        val sink = newSink()
        assertTrue(sink.accepts(44100, 2))
        assertTrue(sink.format.isCompleted)
        assertEquals(PcmFormat(44100, 2), runBlocking { sink.format.await() })
    }

    @Test
    fun sinkRejectsNonPositiveRateOrChannelsBeforeTheFormatIsFixed() {
        val sink = newSink()
        assertFalse(sink.accepts(0, 2))
        assertFalse(sink.accepts(44100, 0))
        assertFalse(sink.accepts(-1, -1))
        assertFalse(sink.format.isCompleted)
        assertTrue(sink.accepts(48000, 1))
        assertEquals(PcmFormat(48000, 1), runBlocking { sink.format.await() })
    }

    @Test
    fun sinkAcceptsTheSameFormatAgain() {
        val sink = newSink()
        assertTrue(sink.accepts(44100, 2))
        assertTrue(sink.accepts(44100, 2))
    }

    @Test
    fun sinkReturnsFalseForALaterFormatMismatch() {
        val sink = newSink()
        assertTrue(sink.accepts(44100, 2))
        assertFalse(sink.accepts(48000, 2))
        assertFalse(sink.accepts(44100, 1))
        assertFalse(sink.accepts(0, 0))
        assertTrue(sink.accepts(44100, 2))
    }

    @Test
    fun decodedStreamCarriesTheFixedFormatAndTheGivenMetadata() {
        val stream = decoding(startMs = 1500, durationMs = 9000, bitRate = 128_000) { sink ->
            sink.accepts(32000, 2)
            sink.write(shorts(0, 8), 8)
        }

        assertEquals(32000, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertEquals(16, stream.bitsPerSample)
        assertEquals(1500L, stream.startMs)
        assertEquals(9000L, stream.durationMs)
        assertEquals(128_000L, stream.bitRate)
        assertEquals((0..7).map { it.toShort() }, stream.samples())
    }

    @Test
    fun trimFramesReceivesTheFixedFormat() {
        var received: PcmFormat? = null
        val stream = decoding(
            trim = {
                received = it
                0L
            }
        ) { sink ->
            sink.accepts(22050, 2)
            sink.write(shorts(0, 4), 4)
        }

        assertEquals(PcmFormat(22050, 2), received)
        stream.samples()
    }

    @Test
    fun trimDropsExactlyTheRequestedFramesAcrossBufferBoundaries() {
        val chunk = PcmAccumulator.FRAMES_PER_CHUNK + 1
        val all = shorts(0, chunk * 3)
        val trim = chunk + 5L
        val stream = decoding(trim = { trim }) { sink ->
            sink.accepts(44100, 1)
            for (part in 0 until 3) sink.write(all.copyOfRange(part * chunk, (part + 1) * chunk), chunk)
        }

        assertEquals(all.toList().drop(trim.toInt()), stream.samples())
    }

    @Test
    fun trimEqualToAWholeBufferDropsThatBufferOnly() {
        val chunk = PcmAccumulator.FRAMES_PER_CHUNK + 1
        val all = shorts(0, chunk * 2)
        val stream = decoding(trim = { chunk.toLong() }) { sink ->
            sink.accepts(44100, 1)
            for (part in 0 until 2) sink.write(all.copyOfRange(part * chunk, (part + 1) * chunk), chunk)
        }

        assertEquals(all.toList().drop(chunk), stream.samples())
    }

    @Test
    fun trimCountsFramesNotSamplesWithinOneBuffer() {
        val stream = decoding(trim = { 2L }) { sink ->
            sink.accepts(44100, 2)
            sink.write(shorts(0, 12), 12)
        }

        assertEquals((4..11).map { it.toShort() }, stream.samples())
    }

    @Test
    fun trimBeyondTheTotalGivesAnEmptyFlow() {
        val stream = decoding(trim = { 100L }) { sink ->
            sink.accepts(44100, 2)
            sink.write(shorts(0, 24), 24)
        }

        assertEquals(emptyList<Short>(), stream.samples())
    }

    @Test
    fun negativeTrimDropsNothing() {
        val stream = decoding(trim = { -7L }) { sink ->
            sink.accepts(44100, 2)
            sink.write(shorts(0, 6), 6)
        }

        assertEquals((0..5).map { it.toShort() }, stream.samples())
    }

    @Test
    fun decodeThrowingBeforeAcceptMakesLaunchDecodingThrowThatException() {
        assertFailsWith<DecodeFailure> {
            decoding { throw DecodeFailure() }
        }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun decodeReturningWithoutAcceptGivesEofException() {
        assertFailsWith<EOFException> {
            decoding { }
        }
    }

    @Test
    fun decodeThatOnlyHadItsFormatRejectedGivesEofException() {
        assertFailsWith<EOFException> {
            decoding { sink -> sink.accepts(0, 0) }
        }
    }

    @Test
    fun decodeThrowingAfterTheFirstBufferFailsTheFlow() {
        val stream = decoding { sink ->
            sink.accepts(44100, 1)
            val block = shorts(0, PcmAccumulator.FRAMES_PER_CHUNK + 1)
            sink.write(block, block.size)
            throw DecodeFailure()
        }

        val failure = assertFailsWith<IOException> {
            runBlocking { withTimeout(10.seconds) { stream.pcmFlow.toList() } }
        }

        assertTrue(failure is DecodeFailure || failure.cause is DecodeFailure)
    }

    @Test
    fun decodeThatFixesNoFormatWithinTheTimeoutFailsAndIsCancelled() {
        val failure = assertFailsWith<IOException> {
            runBlocking {
                launchDecoding(logger, scope, "test", 0, null, null, { 0L }, timeout = 50.milliseconds) {
                    awaitCancellation()
                }
            }
        }

        assertFalse(failure is EOFException)
        runBlocking { withTimeout(10.seconds) { scope.coroutineContext.job.children.forEach { it.join() } } }
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun stoppingTheConsumerCancelsTheDecoder() {
        val stopped = CompletableDeferred<Unit>()
        val stream = decoding { sink ->
            sink.accepts(44100, 1)
            try {
                val block = shorts(0, PcmAccumulator.FRAMES_PER_CHUNK + 1)
                while (true) sink.write(block, block.size)
            } finally {
                stopped.complete(Unit)
            }
        }

        runBlocking { withTimeout(10.seconds) { stream.pcmFlow.first() } }

        runBlocking { withTimeout(10.seconds) { stopped.await() } }
    }

    @Test
    fun everyWrittenSampleReachesTheFlowInOrder() {
        val all = shorts(0, 25_000)
        val stream = decoding { sink ->
            sink.accepts(44100, 1)
            var offset = 0
            while (offset < all.size) {
                val count = minOf(777, all.size - offset)
                sink.write(all.copyOfRange(offset, offset + count), count)
                offset += count
            }
        }

        assertContentEquals(all, stream.samples().toShortArray())
    }
}
