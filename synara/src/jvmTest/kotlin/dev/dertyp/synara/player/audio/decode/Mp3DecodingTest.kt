package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.Mp3Fixtures
import dev.dertyp.synara.player.audio.ProbeInfo
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class Mp3DecodingTest {
    private companion object {
        const val SAMPLES_PER_FRAME = 1152
        const val CHANNELS = 2
    }

    private class SourceFailure : IOException()

    private class TestSource(private val length: Long, private val opener: (Long) -> InputStream?) : AudioSource {
        override val cacheKey: String = "test"
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = length

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? = opener(offset)
    }

    private class FailingStream(private val head: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int = throw SourceFailure()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= head.size) throw SourceFailure()
            val count = minOf(len, head.size - position)
            System.arraycopy(head, position, b, off, count)
            position += count
            return count
        }
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun info(frames: ByteArray) = ProbeInfo.Mp3(
        firstFrameOffset = 0,
        header = assertNotNull(Mp3Header.parseConfirmed(frames, 0)),
        xing = null,
        vbri = null,
    )

    private fun open(frames: ByteArray, opener: (Long) -> InputStream?): DecodedStream = runBlocking {
        assertNotNull(Mp3Decoding.open(logger, TestSource(frames.size.toLong(), opener), info(frames), 0, scope))
    }

    private fun DecodedStream.samples(): List<Short> {
        val buffers = runBlocking { withTimeout(10.seconds) { pcmFlow.toList() } }
        return buffers.flatMap { buffer -> List(buffer.remaining()) { buffer.get() } }
    }

    @Test
    fun silentFramesDecodeToSilenceAndEndTheFlow() {
        val frames = Mp3Fixtures.frames(count = 10)
        val stream = open(frames) { ByteArrayInputStream(frames) }

        assertEquals(44100, stream.sampleRate)
        assertEquals(CHANNELS, stream.channels)
        val samples = stream.samples()
        assertEquals(10 * SAMPLES_PER_FRAME * CHANNELS, samples.size)
        assertTrue(samples.all { it == 0.toShort() })
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun streamFailureAfterTheFirstFramesFailsTheFlow() {
        val frames = Mp3Fixtures.frames(count = 30)
        val stream = open(frames) { FailingStream(frames) }

        assertEquals(44100, stream.sampleRate)
        assertEquals(CHANNELS, stream.channels)
        assertFailsWith<SourceFailure> { stream.samples() }
        verify(exactly = 1) { logger.error(any(), any(), any(), any()) }
    }
}
