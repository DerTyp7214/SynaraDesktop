package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OggOpusSeekTest {
    private class BytesSource(private val bytes: ByteArray, private val ranged: Boolean = true) : AudioSource {
        val opens = CopyOnWriteArrayList<Long>()
        override val cacheKey: String = "ogg-opus-seek"
        override val durationMsHint: Long = DURATION_MS
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            if (offset > 0 && !ranged) return null
            return if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun open(source: AudioSource, positionMs: Long): DecodedStream = runBlocking {
        assertNotNull(AudioDecoders(logger).open(source, positionMs, scope))
    }

    private fun DecodedStream.pcm(): ShortArray = runBlocking {
        withTimeout(30.seconds) {
            val out = mutableListOf<Short>()
            pcmFlow.collect { buffer -> while (buffer.hasRemaining()) out += buffer.get() }
            out.toShortArray()
        }
    }

    private fun relativeError(actual: ShortArray, expected: ShortArray, from: Int, shift: Int, count: Int): Double {
        var error = 0.0
        var energy = 0.0
        for (i in 0 until count) {
            val reference = expected[from + shift + i].toDouble()
            val difference = actual[i] - reference
            error += difference * difference
            energy += reference * reference
        }
        return sqrt(error / energy.coerceAtLeast(1.0))
    }

    private fun assertSeeksExactly(positionMs: Long, source: BytesSource = BytesSource(fixture.bytes)) {
        val stream = open(source, positionMs)
        val pcm = stream.pcm()
        val target = (positionMs * 48).toInt()

        assertEquals(positionMs, stream.startMs)
        assertEquals(reference.size - target, pcm.size)
        val error = relativeError(pcm, reference, target, shift = 0, count = COMPARED)
        assertTrue(error < 0.01, "error $error at $positionMs")
        for (shift in listOf(-1, 1, 960)) {
            val shifted = relativeError(pcm, reference, target, shift, COMPARED)
            assertTrue(shifted > 0.5, "shift $shift matches as well ($shifted)")
        }
        assertTrue(source.opens.size <= 6, "opened ${source.opens}")
    }

    @Test
    fun seeksLandOnTheTargetFrameDespiteUnevenPageSizes() {
        for (positionMs in listOf(35_000L, 40_000L, 50_000L, 58_000L)) assertSeeksExactly(positionMs)
    }

    @Test
    fun seeksIntoTheSilenceLandOnTheTargetFrame() {
        val stream = open(BytesSource(fixture.bytes), 20_000)
        val pcm = stream.pcm()

        assertEquals(20_000L, stream.startMs)
        assertEquals(reference.size - 20_000 * 48, pcm.size)
        assertTrue(pcm.take(9 * 48000).all { it in -2..2 })
        assertTrue(relativeError(pcm, reference, 20_000 * 48, 0, reference.size - 20_000 * 48) < 0.01)
    }

    @Test
    fun aSourceThatCannotBeReadAtAnOffsetDecodesFromTheStart() {
        val stream = open(BytesSource(fixture.bytes, ranged = false), 40_000)
        val pcm = stream.pcm()

        assertEquals(40_000L, stream.startMs)
        assertEquals(reference.size - 40_000 * 48, pcm.size)
        assertEquals(0.0, relativeError(pcm, reference, 40_000 * 48, 0, COMPARED))
    }

    private companion object {
        const val SILENCE_SECONDS = 30
        const val NOISE_SECONDS = 30
        const val DURATION_MS = (SILENCE_SECONDS + NOISE_SECONDS) * 1000L
        const val COMPARED = 9600

        val fixture: OggOpusFixture by lazy {
            val random = Random(5)
            val silence = SILENCE_SECONDS * 48000
            val samples = ShortArray((SILENCE_SECONDS + NOISE_SECONDS) * 48000) {
                if (it < silence) 0 else random.nextInt(-8000, 8000).toShort()
            }
            OggOpusFixture.of(samples, channels = 1, packetsPerPage = 25, vbr = true)
        }

        val reference: ShortArray by lazy {
            val logger = mockk<Logger>(relaxed = true)
            val scope = CoroutineScope(SupervisorJob())
            try {
                runBlocking {
                    val stream = assertNotNull(AudioDecoders(logger).open(BytesSource(fixture.bytes), 0, scope))
                    withTimeout(30.seconds) {
                        val out = mutableListOf<Short>()
                        stream.pcmFlow.collect { buffer -> while (buffer.hasRemaining()) out += buffer.get() }
                        out.toShortArray()
                    }
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
