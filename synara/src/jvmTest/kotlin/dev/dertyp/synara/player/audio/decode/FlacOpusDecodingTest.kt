package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FlacOpusDecodingTest {
    private class BytesSource(private val bytes: ByteArray) : AudioSource {
        override val cacheKey: String = "test"
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? =
            if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun flacMetadata(sampleRate: Int, channels: Int, bitsPerSample: Int, totalSamples: Long): ByteArray {
        val streamInfo = ByteArray(34)
        streamInfo[10] = (sampleRate shr 12).toByte()
        streamInfo[11] = (sampleRate shr 4).toByte()
        streamInfo[12] = (((sampleRate and 0x0F) shl 4) or ((channels - 1) shl 1) or ((bitsPerSample - 1) shr 4)).toByte()
        streamInfo[13] = ((((bitsPerSample - 1) and 0x0F) shl 4) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
        streamInfo[14] = (totalSamples shr 24).toByte()
        streamInfo[15] = (totalSamples shr 16).toByte()
        streamInfo[16] = (totalSamples shr 8).toByte()
        streamInfo[17] = totalSamples.toByte()
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo
    }

    private fun open(metadata: ByteArray): DecodedStream = runBlocking {
        assertNotNull(
            FlacOpusDecoding.open(logger, BytesSource(metadata), metadata, isOgg = false, positionMs = 0, totalDuration = 0, scope = scope)
        )
    }

    private fun DecodedStream.drain() = runBlocking { withTimeout(10.seconds) { pcmFlow.toList() } }

    @Test
    fun flacStreamCarriesTheFormatAndTheDurationOfTheStreamInfo() {
        val stream = open(flacMetadata(sampleRate = 44100, channels = 2, bitsPerSample = 16, totalSamples = 88_200))

        assertEquals(44100, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertEquals(16, stream.bitsPerSample)
        assertEquals(2000L, stream.durationMs)
        assertTrue(stream.drain().isEmpty())
    }

    @Test
    fun flacStreamLongerThanThirtyTwoBitsOfSamplesCarriesItsDuration() {
        val totalSamples = 96_000L * 3600 * 20
        val stream = open(flacMetadata(sampleRate = 96_000, channels = 2, bitsPerSample = 24, totalSamples = totalSamples))

        assertEquals(96_000, stream.sampleRate)
        assertEquals(24, stream.bitsPerSample)
        assertEquals(20L * 3600 * 1000, stream.durationMs)
        stream.drain()
    }

    @Test
    fun flacStreamWithoutATotalSampleCountCarriesNoDuration() {
        val stream = open(flacMetadata(sampleRate = 44100, channels = 2, bitsPerSample = 16, totalSamples = 0))

        assertEquals(44100, stream.sampleRate)
        assertNull(stream.durationMs)
        stream.drain()
    }
}
