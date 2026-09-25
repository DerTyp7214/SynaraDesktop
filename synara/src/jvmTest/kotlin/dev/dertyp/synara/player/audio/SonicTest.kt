package dev.dertyp.synara.player.audio

import kotlinx.coroutines.runBlocking
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SonicTest {
    private val sampleRate = 44100
    private val channels = 2

    private fun tone(frames: Int): ShortArray = ShortArray(frames * channels) { index ->
        val frame = index / channels
        (sin(2 * PI * 220.0 * frame / sampleRate) * 12000).toInt().toShort()
    }

    private fun stretch(input: ShortArray, speed: Float, chunkFrames: Int = 4096): Int {
        val sonic = Sonic(sampleRate, channels)
        sonic.speed = speed
        val frames = input.size / channels
        var produced = 0
        var position = 0
        val out = ShortBuffer.allocate(1 shl 20)
        while (position < frames) {
            val count = minOf(chunkFrames, frames - position)
            sonic.queueInput(ShortBuffer.wrap(input, position * channels, count * channels))
            position += count
            out.clear()
            sonic.getOutput(out)
            produced += out.position() / channels
        }
        sonic.queueEndOfStream()
        out.clear()
        sonic.getOutput(out)
        produced += out.position() / channels
        return produced
    }

    @Test
    fun outputLengthFollowsSpeed() {
        val frames = sampleRate * 3
        val input = tone(frames)
        for (speed in listOf(1.5f, 2f, 0.8f)) {
            val produced = stretch(input, speed)
            val expected = frames / speed
            assertTrue(abs(produced - expected) / expected < 0.02, "speed $speed produced $produced, expected about $expected")
        }
    }

    @Test
    fun unitSpeedPassesSamplesThrough() {
        val input = tone(10_000)
        val sonic = Sonic(sampleRate, channels)
        sonic.queueInput(ShortBuffer.wrap(input))
        val out = ShortBuffer.allocate(input.size)
        sonic.getOutput(out)
        assertContentEquals(input, out.array())
        assertTrue(sonic.isEmpty)
    }

    @Test
    fun stagePassesThroughAtUnitSpeed() = runBlocking {
        val chunks = ArrayDeque(List(3) { ShortBuffer.wrap(tone(4410)) })
        val originals = chunks.toList()
        val stage = TimeStretchStage(sampleRate, channels, { 1f }) { chunks.removeFirstOrNull() }
        for (original in originals) {
            val chunk = stage.next()!!
            assertSame(original, chunk.pcm)
            assertEquals(4410, chunk.outputFrames)
            assertEquals(4410L, chunk.contentFrames)
        }
        assertNull(stage.next())
    }

    @Test
    fun stageMapsContentFramesAtDoubleSpeed() = runBlocking {
        val total = sampleRate * 2
        val source = tone(total)
        var offset = 0
        val stage = TimeStretchStage(sampleRate, channels, { 2f }) {
            if (offset >= total) null
            else {
                val count = minOf(2048, total - offset)
                ShortBuffer.wrap(source, offset * channels, count * channels).slice().also { offset += count }
            }
        }
        var output = 0L
        var content = 0L
        while (true) {
            val chunk = stage.next() ?: break
            assertTrue(chunk.outputFrames >= sampleRate * 150 / 1000 || offset >= total)
            output += chunk.outputFrames
            content += chunk.contentFrames
        }
        assertTrue(abs(output - total / 2.0) / (total / 2.0) < 0.02, "output $output")
        assertTrue(abs(content - total.toDouble()) / total < 0.02, "content $content")
    }
}
