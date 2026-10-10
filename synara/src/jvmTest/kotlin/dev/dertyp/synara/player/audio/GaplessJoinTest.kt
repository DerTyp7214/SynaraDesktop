package dev.dertyp.synara.player.audio

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.decode.AudioDecoders
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class GaplessJoinTest {
    private class BytesSource(private val bytes: ByteArray) : AudioSource {
        override val cacheKey: String = "test"
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? =
            if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    private class Decoded(val channels: Int, val samples: ShortArray) {
        val frames: Int get() = samples.size / channels
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())
    private val decoders = AudioDecoders(logger)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun tone(frequency: Double, amplitude: Int, from: Int, until: Int): ShortArray =
        ShortArray(until - from) { (amplitude * sin(2 * PI * frequency * (from + it) / SAMPLE_RATE)).roundToInt().toShort() }

    private fun channelTones(channels: Int, from: Int, until: Int): List<ShortArray> =
        List(channels) { tone(FREQUENCIES[it], 12_000, from, until) }

    private fun interleave(channelSamples: List<ShortArray>): ShortArray {
        val channels = channelSamples.size
        val frames = channelSamples.first().size
        return ShortArray(frames * channels) { channelSamples[it % channels][it / channels] }
    }

    private fun decode(bytes: ByteArray): Decoded = runBlocking {
        withTimeout(10.seconds) {
            val stream = assertNotNull(decoders.open(BytesSource(bytes), 0L, scope))
            assertEquals(SAMPLE_RATE, stream.sampleRate)
            assertEquals(16, stream.bitsPerSample)
            val samples = ArrayList<Short>()
            for (buffer in stream.pcmFlow.toList()) while (buffer.hasRemaining()) samples.add(buffer.get())
            Decoded(stream.channels, samples.toShortArray())
        }
    }

    private fun assertSampleExactJoin(channels: Int, encode: (List<ShortArray>) -> ByteArray) {
        val cut = TOTAL_FRAMES / 2 + 333
        val first = decode(encode(channelTones(channels, 0, cut)))
        val second = decode(encode(channelTones(channels, cut, TOTAL_FRAMES)))

        assertEquals(channels, first.channels)
        assertEquals(channels, second.channels)
        assertEquals(cut, first.frames)
        assertEquals(TOTAL_FRAMES - cut, second.frames)

        val joined = first.samples + second.samples
        val expected = interleave(channelTones(channels, 0, TOTAL_FRAMES))
        assertEquals(TOTAL_FRAMES * channels, joined.size)
        assertContentEquals(expected, joined)

        val boundary = cut * channels
        assertContentEquals(expected.copyOfRange(boundary - channels, boundary), first.samples.copyOfRange(first.samples.size - channels, first.samples.size))
        assertContentEquals(expected.copyOfRange(boundary, boundary + channels), second.samples.copyOfRange(0, channels))
    }

    @Test
    fun monoFlacHalvesCutInsideABlockDecodeToTheContinuousToneSampleForSample() {
        assertSampleExactJoin(1) { FlacFixtures.verbatimStream44100Bit16(it, FLAC_BLOCK_SIZE) }
    }

    @Test
    fun stereoFlacHalvesCutInsideABlockDecodeToTheContinuousToneSampleForSample() {
        assertSampleExactJoin(2) { FlacFixtures.verbatimStream44100Bit16(it, FLAC_BLOCK_SIZE) }
    }

    @Test
    fun monoWavHalvesDecodeToTheContinuousToneSampleForSample() {
        assertSampleExactJoin(1) { WavFixtures.pcm16(SAMPLE_RATE, it) }
    }

    @Test
    fun stereoWavHalvesDecodeToTheContinuousToneSampleForSample() {
        assertSampleExactJoin(2) { WavFixtures.pcm16(SAMPLE_RATE, it) }
    }

    @Test
    fun flacStreamWhoseLengthIsNotAMultipleOfTheBlockSizeDecodesTheShortLastFrameInFull() {
        val frames = FLAC_BLOCK_SIZE * 3 + 1
        val channelSamples = channelTones(2, 0, frames)

        val decoded = decode(FlacFixtures.verbatimStream44100Bit16(channelSamples, FLAC_BLOCK_SIZE))

        assertEquals(frames, decoded.frames)
        assertContentEquals(interleave(channelSamples), decoded.samples)
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val TOTAL_FRAMES = 10_000
        const val FLAC_BLOCK_SIZE = 1000
        val FREQUENCIES = doubleArrayOf(440.0, 659.25)
    }
}
