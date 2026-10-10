package dev.dertyp.synara.player.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.nio.ShortBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmAccumulatorTest {
    private class Sent(val position: Int, val samples: List<Short>)

    private val target = Channel<ShortBuffer>(Channel.UNLIMITED)
    private val accumulator = PcmAccumulator(channels = 2, target = target, framesPerChunk = 4)

    private fun shorts(start: Int, count: Int) = ShortArray(count) { (start + it).toShort() }

    private fun sent(): List<Sent> {
        target.close()
        val out = mutableListOf<Sent>()
        while (true) {
            val buffer = target.tryReceive().getOrNull() ?: break
            val position = buffer.position()
            out.add(Sent(position, List(buffer.remaining()) { buffer.get() }))
        }
        return out
    }

    @Test
    fun exactFillIsHeldUntilFlushed() = runBlocking {
        accumulator.append(shorts(0, 4), 4)
        accumulator.append(shorts(4, 4), 4)
        assertFalse(target.tryReceive().isSuccess)

        accumulator.flush()

        val buffers = sent()
        assertEquals(1, buffers.size)
        assertEquals((0..7).map { it.toShort() }, buffers[0].samples)
    }

    @Test
    fun overflowSplitsIntoTwoBuffersInOrder() = runBlocking {
        accumulator.append(shorts(0, 6), 6)
        accumulator.append(shorts(6, 6), 6)
        accumulator.flush()

        val buffers = sent()
        assertEquals(2, buffers.size)
        assertEquals((0..5).map { it.toShort() }, buffers[0].samples)
        assertEquals((6..11).map { it.toShort() }, buffers[1].samples)
    }

    @Test
    fun oversizedAppendEmitsPendingDataFirstThenTheBigBlock() = runBlocking {
        accumulator.append(shorts(0, 3), 3)
        accumulator.append(shorts(100, 20), 20)
        accumulator.flush()

        val buffers = sent()
        assertEquals(2, buffers.size)
        assertEquals((0..2).map { it.toShort() }, buffers[0].samples)
        assertEquals((100..119).map { it.toShort() }, buffers[1].samples)
    }

    @Test
    fun oversizedAppendSendsOnlyTheGivenLength() = runBlocking {
        accumulator.append(shorts(0, 30), 12)

        val buffers = sent()
        assertEquals(1, buffers.size)
        assertEquals((0..11).map { it.toShort() }, buffers[0].samples)
    }

    @Test
    fun appendCopiesOnlyTheGivenLengthIntoTheBuffer() = runBlocking {
        accumulator.append(shorts(0, 8), 3)
        accumulator.flush()

        val buffers = sent()
        assertEquals(1, buffers.size)
        assertEquals((0..2).map { it.toShort() }, buffers[0].samples)
    }

    @Test
    fun flushWithNothingPendingSendsNothing() = runBlocking {
        accumulator.flush()
        accumulator.append(shorts(0, 3), 3)
        accumulator.flush()
        accumulator.flush()

        assertEquals(1, sent().size)
    }

    @Test
    fun totalSamplesOutEqualAndMatchSamplesIn() = runBlocking {
        val lengths = listOf(1, 5, 8, 2, 13, 3, 3, 7, 20, 1)
        var next = 0
        val expected = mutableListOf<Short>()
        for (length in lengths) {
            val block = shorts(next, length)
            expected.addAll(block.toList())
            accumulator.append(block, length)
            next += length
        }
        accumulator.flush()

        val buffers = sent()
        assertEquals(expected, buffers.flatMap { it.samples })
        assertTrue(buffers.all { it.samples.size <= 20 })
    }

    @Test
    fun sentBuffersAreFlipped() = runBlocking {
        accumulator.append(shorts(0, 5), 5)
        accumulator.flush()
        accumulator.append(shorts(0, 20), 20)

        val buffers = sent()
        assertEquals(2, buffers.size)
        assertEquals(listOf(0, 0), buffers.map { it.position })
        assertEquals(listOf(5, 20), buffers.map { it.samples.size })
    }

    @Test
    fun sentBufferIsNotChangedByLaterAppends() = runBlocking {
        accumulator.append(shorts(0, 3), 3)
        accumulator.flush()
        accumulator.append(shorts(50, 2), 2)
        accumulator.flush()

        val buffers = sent()
        assertEquals((0..2).map { it.toShort() }, buffers[0].samples)
        assertEquals(listOf<Short>(50, 51), buffers[1].samples)
    }

    @Test
    fun nonPositiveLengthIsIgnored() = runBlocking {
        accumulator.append(shorts(0, 4), 0)
        accumulator.append(shorts(0, 4), -3)
        accumulator.flush()

        assertEquals(0, sent().size)
    }
}
