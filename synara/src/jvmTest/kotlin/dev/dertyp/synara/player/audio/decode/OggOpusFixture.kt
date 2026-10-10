package dev.dertyp.synara.player.audio.decode

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.gagravarr.opus.OpusAudioData
import org.gagravarr.opus.OpusFile
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusTags
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal class OggOpusFixture(
    val bytes: ByteArray,
    val channels: Int,
    val preSkip: Int,
    val packets: Int,
    val packetsPerPage: Int
) {
    val decodedFrames: Int get() = packets * PACKET_FRAMES - preSkip

    val framesBeforeLastPage: Int get() = (packets - 1) / packetsPerPage * packetsPerPage * PACKET_FRAMES - preSkip

    val audioStart: Int get() = pageOffsets().first { granuleAt(it) > 0 }

    fun pageOffsets(): List<Int> {
        val offsets = mutableListOf<Int>()
        var position = 0
        while (position + 27 <= bytes.size && isPageAt(position)) {
            offsets += position
            position = pageEnd(position)
        }
        return offsets
    }

    fun audioPacketsBefore(end: Int): Int {
        var position = 0
        var count = 0
        while (position + 27 <= bytes.size && isPageAt(position) && pageEnd(position) <= end) {
            val segments = bytes[position + 26].toInt() and 0xFF
            for (segment in 0 until segments) {
                if ((bytes[position + 27 + segment].toInt() and 0xFF) < 255) count++
            }
            position = pageEnd(position)
        }
        return (count - 2).coerceAtLeast(0)
    }

    private fun pageEnd(position: Int): Int {
        val segments = bytes[position + 26].toInt() and 0xFF
        var payload = 0
        for (segment in 0 until segments) payload += bytes[position + 27 + segment].toInt() and 0xFF
        return position + 27 + segments + payload
    }

    private fun granuleAt(position: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[position + 6 + i].toLong() and 0xFF)
        return value
    }

    fun packetsFrom(offset: Int): Int {
        var position = offset
        while (position + 4 <= bytes.size && !isPageAt(position)) position++
        var count = 0
        while (position + 27 <= bytes.size && isPageAt(position)) {
            val segments = bytes[position + 26].toInt() and 0xFF
            var payload = 0
            for (segment in 0 until segments) {
                val lacing = bytes[position + 27 + segment].toInt() and 0xFF
                payload += lacing
                if (lacing < 255) count++
            }
            position += 27 + segments + payload
        }
        return count
    }

    private fun isPageAt(position: Int): Boolean =
        bytes[position] == 'O'.code.toByte() && bytes[position + 1] == 'g'.code.toByte() &&
            bytes[position + 2] == 'g'.code.toByte() && bytes[position + 3] == 'S'.code.toByte()

    companion object {
        const val PACKET_FRAMES = 960
        const val PRE_SKIP = 312
        private const val SAMPLE_RATE = 48000
        private const val SERIAL = 0x4F707573

        fun tone(
            frames: Int,
            channels: Int = 2,
            packetsPerPage: Int = 1,
            tagsPadding: Int = 0,
            endGranule: (Long) -> Long = { it }
        ): OggOpusFixture {
            val packets = (frames + PRE_SKIP + PACKET_FRAMES - 1) / PACKET_FRAMES
            val pcm = ShortArray(packets * PACKET_FRAMES * channels)
            for (frame in 0 until frames) {
                val sample = (sin(2 * PI * 440 * frame / SAMPLE_RATE) * 8000).toInt().toShort()
                for (channel in 0 until channels) pcm[frame * channels + channel] = sample
            }

            val encoder = OpusEncoder(SAMPLE_RATE, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
            encoder.setBitrate(128_000)
            encoder.setUseVBR(false)

            val info = OpusInfo()
            info.setNumChannels(channels)
            info.setPreSkip(PRE_SKIP)
            info.setSampleRate(SAMPLE_RATE.toLong())

            val tags = OpusTags()
            if (tagsPadding > 0) tags.addComment("METADATA_BLOCK_PICTURE", "x".repeat(tagsPadding))

            val out = ByteArrayOutputStream()
            val file = OpusFile(out, SERIAL, info, tags)
            val encoded = ByteArray(4000)
            for (packet in 0 until packets) {
                val length = encoder.encode(pcm, packet * PACKET_FRAMES * channels, PACKET_FRAMES, encoded, 0, encoded.size)
                val audio = OpusAudioData(encoded.copyOf(length))
                if (packet % packetsPerPage == 0) {
                    val pageEnd = min(packet + packetsPerPage, packets)
                    val granule = if (pageEnd == packets) endGranule(PRE_SKIP + frames.toLong()) else pageEnd.toLong() * PACKET_FRAMES
                    audio.setGranulePosition(granule)
                }
                file.writeAudioData(audio)
            }
            file.close()
            return OggOpusFixture(out.toByteArray(), channels, PRE_SKIP, packets, packetsPerPage)
        }
    }
}
