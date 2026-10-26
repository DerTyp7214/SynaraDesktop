package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.PcmAccumulator
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpusEndTrimTest {
    private class BytesSource(private val bytes: ByteArray, override val durationMsHint: Long?) : AudioSource {
        val opens = CopyOnWriteArrayList<Long>()
        override val cacheKey: String = "opus-end-trim"
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            return if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    private class Decoded(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val startMs: Long,
        val durationMs: Long?,
        val chunks: List<Int>,
        val samples: ShortArray,
        val opens: List<Long>
    ) {
        val frames: Int get() = samples.size / channels
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())
    private val frames = 2 * 48000 + 123

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun decode(fixture: OggOpusFixture, startMs: Long = 0, durationMsHint: Long? = null): Decoded = runBlocking {
        val source = BytesSource(fixture.bytes, durationMsHint)
        val stream = assertNotNull(AudioDecoders(logger).open(source, startMs, scope))
        val buffers = withTimeout(30.seconds) { stream.pcmFlow.toList() }
        val samples = ShortArray(buffers.sumOf { it.remaining() })
        var position = 0
        val chunks = buffers.map { buffer ->
            val length = buffer.remaining()
            buffer.get(samples, position, length)
            position += length
            length
        }
        Decoded(stream.sampleRate, stream.channels, stream.bitsPerSample, stream.startMs, stream.durationMs, chunks, samples, source.opens.toList())
    }

    private fun verifyWarnings(count: Int) = verify(exactly = count) { logger.warning(any(), any(), any()) }

    @Test
    fun totalFramesIsTheEndGranuleWithoutThePreSkip() {
        assertEquals(48_123L, OpusEndTrim.totalFrames(48_435, 312))
        assertEquals(0L, OpusEndTrim.totalFrames(312, 312))
        assertEquals(960L, OpusEndTrim.totalFrames(960, 0))
        assertEquals(Long.MAX_VALUE - 312, OpusEndTrim.totalFrames(Long.MAX_VALUE, 312))
    }

    @Test
    fun totalFramesIsUnknownForAMissingGranuleOrOneBelowThePreSkip() {
        assertNull(OpusEndTrim.totalFrames(-1, 312))
        assertNull(OpusEndTrim.totalFrames(-1, 0))
        assertNull(OpusEndTrim.totalFrames(Long.MIN_VALUE, 312))
        assertNull(OpusEndTrim.totalFrames(0, 312))
        assertNull(OpusEndTrim.totalFrames(311, 312))
    }

    @Test
    fun framesToKeepCutsThePacketThatCrossesTheTotal() {
        assertEquals(435, OpusEndTrim.framesToKeep(48_123, 47_688, 960))
        assertEquals(1, OpusEndTrim.framesToKeep(48_123, 48_122, 960))
        assertEquals(959, OpusEndTrim.framesToKeep(48_123, 47_164, 960))
    }

    @Test
    fun framesToKeepLeavesPacketsBeforeTheTotalWhole() {
        assertEquals(960, OpusEndTrim.framesToKeep(48_123, 0, 960))
        assertEquals(960, OpusEndTrim.framesToKeep(48_123, 47_163, 960))
        assertEquals(960, OpusEndTrim.framesToKeep(Long.MAX_VALUE, 0, 960))
        assertEquals(5760, OpusEndTrim.framesToKeep(1_000_000, 10, 5760))
    }

    @Test
    fun framesToKeepIsZeroOnceTheTotalIsReachedAndNeverNegative() {
        assertEquals(0, OpusEndTrim.framesToKeep(48_123, 48_123, 960))
        assertEquals(0, OpusEndTrim.framesToKeep(100, 48_000, 960))
        assertEquals(0, OpusEndTrim.framesToKeep(0, Long.MAX_VALUE, 960))
        assertEquals(0, OpusEndTrim.framesToKeep(0, 0, 960))
        assertEquals(0, OpusEndTrim.framesToKeep(48_123, 0, 0))
        assertEquals(0, OpusEndTrim.framesToKeep(48_123, 0, -960))
    }

    @Test
    fun framesToKeepKeepsEverythingWithoutAKnownTotal() {
        assertEquals(960, OpusEndTrim.framesToKeep(null, 0, 960))
        assertEquals(960, OpusEndTrim.framesToKeep(null, Long.MAX_VALUE, 960))
        assertEquals(0, OpusEndTrim.framesToKeep(null, 0, -1))
    }

    @Test
    fun streamDecodedFromItsStartEndsAtTheGranuleOfTheLastPage() {
        val fixture = OggOpusFixture.tone(frames)
        assertTrue(fixture.decodedFrames > frames)

        val decoded = decode(fixture)

        assertEquals(48000, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(16, decoded.bitsPerSample)
        assertEquals(0L, decoded.startMs)
        assertEquals(frames * 1000L / 48000, decoded.durationMs)
        assertEquals(listOf(0L, 0L, 0L), decoded.opens)
        assertEquals(frames, decoded.frames)
        assertTrue(decoded.chunks.all { it in 1..PcmAccumulator.FRAMES_PER_CHUNK * 2 })
        assertTrue(decoded.samples.any { it.toInt() != 0 })
        verifyWarnings(0)
    }

    @Test
    fun monoStreamEndsAtTheGranuleOfTheLastPage() {
        val decoded = decode(OggOpusFixture.tone(frames, channels = 1))

        assertEquals(1, decoded.channels)
        assertEquals(frames, decoded.frames)
    }

    @Test
    fun streamThatEndsOnAPacketBoundaryLosesNothing() {
        val exact = 100 * OggOpusFixture.PACKET_FRAMES - OggOpusFixture.PRE_SKIP
        val fixture = OggOpusFixture.tone(exact)
        assertEquals(exact, fixture.decodedFrames)

        assertEquals(exact, decode(fixture).frames)
        verifyWarnings(0)
    }

    @Test
    fun streamWithSeveralPacketsPerPageEndsAtTheGranuleOfTheLastPage() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)
        assertTrue(fixture.framesBeforeLastPage < frames)

        assertEquals(frames, decode(fixture).frames)
        verifyWarnings(0)
    }

    @Test
    fun endGranuleThatCutsSeveralPacketsOfTheLastPageIsHonoured() {
        val shortened = frames - 2000
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8) { it - 2000 }
        assertTrue(fixture.framesBeforeLastPage < shortened)
        assertTrue(fixture.decodedFrames - shortened > 2 * OggOpusFixture.PACKET_FRAMES)

        assertEquals(shortened, decode(fixture).frames)
        verifyWarnings(0)
    }

    @Test
    fun trimmingRemovesOnlyTheTailOfTheDecodedAudio() {
        val untrimmedFixture = OggOpusFixture.tone(frames) { it + 100_000 }
        val untrimmed = decode(untrimmedFixture)
        val trimmed = decode(OggOpusFixture.tone(frames))

        assertEquals(untrimmedFixture.decodedFrames, untrimmed.frames)
        assertContentEquals(untrimmed.samples.copyOf(frames * 2), trimmed.samples)
        verifyWarnings(0)
    }

    @Test
    fun streamDecodedFromAPageIsTrimmedAtTheGranuleOfTheLastPage() {
        val fixture = OggOpusFixture.tone(LONG_FRAMES, packetsPerPage = 8)

        val decoded = decode(fixture, startMs = 20_000, durationMsHint = LONG_FRAMES * 1000L / 48000)

        assertTrue(decoded.opens.last() > 0)
        assertEquals(20_000L, decoded.startMs)
        assertEquals(LONG_FRAMES - 20_000 * 48, decoded.frames)
        verifyWarnings(0)
    }

    @Test
    fun streamDecodedFromAPageDropsALastPageBehindAnEndGranuleBelowItsFrameCount() {
        val fixture = OggOpusFixture.tone(LONG_FRAMES, packetsPerPage = 8) { OggOpusFixture.PRE_SKIP + 1000L }

        val decoded = decode(fixture, startMs = 20_000, durationMsHint = LONG_FRAMES * 1000L / 48000)

        assertTrue(decoded.opens.last() > 0)
        assertEquals(fixture.framesBeforeLastPage - 20_000 * 48, decoded.frames)
        verifyWarnings(1)
    }

    @Test
    fun endGranuleBeforeTheLastPageDropsThatPageOnlyAndIsReportedOnce() {
        val fixture = OggOpusFixture.tone(frames) { OggOpusFixture.PRE_SKIP + 1000L }

        assertEquals(fixture.framesBeforeLastPage, decode(fixture).frames)
        verifyWarnings(1)
    }

    @Test
    fun endGranuleBeforeALastPageOfSeveralPacketsDropsThatPageOnlyAndIsReportedOnce() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8) { OggOpusFixture.PRE_SKIP + 1000L }

        assertEquals(fixture.framesBeforeLastPage, decode(fixture).frames)
        verifyWarnings(1)
    }

    @Test
    fun endGranuleOfZeroLeavesTheStreamUntrimmedAndIsReportedOnce() {
        val fixture = OggOpusFixture.tone(frames) { 0L }

        assertEquals(fixture.decodedFrames, decode(fixture).frames)
        verifyWarnings(1)
    }

    @Test
    fun endGranuleBelowThePreSkipLeavesTheStreamUntrimmedAndIsReportedOnce() {
        val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8) { OggOpusFixture.PRE_SKIP - 1L }

        assertEquals(fixture.decodedFrames, decode(fixture).frames)
        verifyWarnings(1)
    }

    @Test
    fun endGranuleBeyondTheDecodedAudioLeavesTheStreamUntrimmed() {
        val fixture = OggOpusFixture.tone(frames) { it + 100_000 }

        assertEquals(fixture.decodedFrames, decode(fixture).frames)
        verifyWarnings(0)
    }

    private companion object {
        const val LONG_FRAMES = 30 * 48000 + 123
    }
}
