package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioProbe
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.ProbeInfo
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

// Fixtures are 22.05 and 32 kHz mono VBR files with silence, noise and a tone, so byte position and time diverge.
internal object SeekFixtures {
    const val MPEG1 = "vbr-mpeg1-32k.mp3"
    const val MPEG2 = "vbr-mpeg2-22k.mp3"
    const val ADTS = "vbr-lc-22k.aac"

    fun bytes(name: String): ByteArray =
        assertNotNull(SeekFixtures::class.java.getResourceAsStream("/audio/$name"), name).use { it.readBytes() }

    class BytesSource(private val data: ByteArray) : AudioSource {
        val opens = mutableListOf<Long>()

        override val cacheKey: String = UUID.randomUUID().toString()
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = data.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            synchronized(opens) { opens.add(offset) }
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    fun probe(source: AudioSource): ProbeInfo = runBlocking { assertNotNull(AudioProbe(mockk(relaxed = true)).probe(source)) }

    fun DecodedStream.samples(): ShortArray {
        val buffers = runBlocking { withTimeout(30.seconds) { pcmFlow.toList() } }
        val out = ShortArray(buffers.sumOf { it.remaining() })
        var position = 0
        for (buffer in buffers) {
            val count = buffer.remaining()
            buffer.get(out, position, count)
            position += count
        }
        return out
    }
}
