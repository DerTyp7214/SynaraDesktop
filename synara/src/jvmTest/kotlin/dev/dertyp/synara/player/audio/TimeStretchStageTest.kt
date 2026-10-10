package dev.dertyp.synara.player.audio

import kotlinx.coroutines.runBlocking
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeStretchStageTest {
    private val sampleRate = 44100
    private val channels = 2

    private class Upstream(private val buffers: List<ShortBuffer?>) {
        private val queue = ArrayDeque(buffers)
        var calls = 0
            private set

        suspend fun next(): ShortBuffer? {
            calls++
            return queue.removeFirstOrNull()
        }
    }

    private fun tone(frames: Int): ShortArray = ShortArray(frames * channels) { index ->
        val frame = index / channels
        (sin(2 * PI * 220.0 * frame / sampleRate) * 12000).toInt().toShort()
    }

    private fun slices(totalFrames: Int, chunkFrames: Int = 2048): List<ShortBuffer> {
        val source = tone(totalFrames)
        val out = mutableListOf<ShortBuffer>()
        var offset = 0
        while (offset < totalFrames) {
            val count = minOf(chunkFrames, totalFrames - offset)
            out.add(ShortBuffer.wrap(source, offset * channels, count * channels).slice())
            offset += count
        }
        return out
    }

    private fun drain(stage: TimeStretchStage): List<PcmChunk> = runBlocking {
        val out = mutableListOf<PcmChunk>()
        while (true) out.add(stage.next() ?: break)
        out
    }

    private fun totals(speed: Float, totalFrames: Int): Pair<Long, Long> {
        val upstream = Upstream(slices(totalFrames))
        val chunks = drain(TimeStretchStage(sampleRate, channels, { speed }) { upstream.next() })
        return chunks.sumOf { it.outputFrames.toLong() } to chunks.sumOf { it.contentFrames }
    }

    @Test
    fun speedAboveMaximumIsClampedToMaximum() {
        val frames = sampleRate * 2
        val clamped = totals(10f, frames)

        assertEquals(totals(3f, frames), clamped)
        assertTrue(abs(clamped.first - frames / 3.0) / (frames / 3.0) < 0.05, "output ${clamped.first}")
    }

    @Test
    fun speedBelowMinimumIsClampedToMinimum() {
        val frames = sampleRate * 2
        val clamped = totals(0.1f, frames)

        assertEquals(totals(0.5f, frames), clamped)
        assertTrue(abs(clamped.first - frames * 2.0) / (frames * 2.0) < 0.05, "output ${clamped.first}")
    }

    @Test
    fun emptyUpstreamBuffersAreSkipped() = runBlocking {
        val real = ShortBuffer.wrap(tone(1000))
        val upstream = Upstream(listOf(ShortBuffer.allocate(0), ShortBuffer.allocate(1), real))
        val stage = TimeStretchStage(sampleRate, channels, { 1f }) { upstream.next() }

        val chunk = assertNotNull(stage.next())

        assertSame(real, chunk.pcm)
        assertEquals(1000, chunk.outputFrames)
        assertEquals(3, upstream.calls)
    }

    @Test
    fun upstreamEndGivesNullAndStaysEnded() = runBlocking {
        val upstream = Upstream(listOf(null, ShortBuffer.wrap(tone(1000))))
        val stage = TimeStretchStage(sampleRate, channels, { 1f }) { upstream.next() }

        assertNull(stage.next())
        assertNull(stage.next())
        assertNull(stage.next())
        assertEquals(1, upstream.calls)
    }

    @Test
    fun upstreamEndStaysEndedAtChangedSpeed() = runBlocking {
        val upstream = Upstream(listOf(ShortBuffer.wrap(tone(20000)), null, ShortBuffer.wrap(tone(1000))))
        var speed = 2f
        val stage = TimeStretchStage(sampleRate, channels, { speed }) { upstream.next() }

        while (stage.next() != null) Unit
        speed = 1f

        assertNull(stage.next())
        assertEquals(2, upstream.calls)
    }

    @Test
    fun tailIsFlushedAfterUpstreamEndsAtChangedSpeed() = runBlocking {
        val inputFrames = 4410
        val upstream = Upstream(listOf(ShortBuffer.wrap(tone(inputFrames)), null))
        val stage = TimeStretchStage(sampleRate, channels, { 2f }) { upstream.next() }

        val chunks = drain(stage)

        assertEquals(1, chunks.size)
        val expected = inputFrames / 2.0
        assertTrue(abs(chunks[0].outputFrames - expected) / expected < 0.05, "output ${chunks[0].outputFrames}")
        assertTrue(abs(chunks[0].contentFrames - inputFrames.toDouble()) / inputFrames < 0.05, "content ${chunks[0].contentFrames}")
        assertEquals(2, upstream.calls)
    }

    @Test
    fun outputBuffersHoldFramesTimesChannelsShortsAndAreFlipped() {
        val upstream = Upstream(slices(sampleRate))
        val chunks = drain(TimeStretchStage(sampleRate, channels, { 1.5f }) { upstream.next() })

        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertEquals(0, chunk.pcm.position())
            assertEquals(chunk.outputFrames * channels, chunk.pcm.remaining())
        }
    }
}
