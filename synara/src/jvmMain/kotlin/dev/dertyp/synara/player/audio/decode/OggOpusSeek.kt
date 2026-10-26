package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.matches
import dev.dertyp.synara.player.audio.u8
import kotlinx.coroutines.CoroutineScope

internal class OggPageHeader(
    val length: Int,
    val continued: Boolean,
    val granulePosition: Long,
    val sid: Int,
    val sequenceNumber: Long
) {
    val hasGranule: Boolean get() = granulePosition != NO_GRANULE

    companion object {
        private const val HEADER_BYTES = 27
        private const val NO_GRANULE = -1L

        fun parse(buffer: ByteArray, offset: Int, length: Int): OggPageHeader? {
            if (offset + HEADER_BYTES > length || !buffer.matches(offset, "OggS") || buffer.u8(offset + 4) != 0) return null
            val segments = buffer.u8(offset + 26)
            val table = offset + HEADER_BYTES
            if (table + segments > length) return null
            var payload = 0
            for (segment in 0 until segments) payload += buffer.u8(table + segment)
            val end = table + segments + payload
            if (end > length) return null
            if (crc(buffer, offset, end) != le32(buffer, offset + 22)) return null
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or buffer.u8(offset + 6 + i).toLong()
            return OggPageHeader(
                length = end - offset,
                continued = buffer.u8(offset + 5) and 0x01 != 0,
                granulePosition = granule,
                sid = le32(buffer, offset + 14).toInt(),
                sequenceNumber = le32(buffer, offset + 18)
            )
        }

        fun complete(buffer: ByteArray, offset: Int, length: Int): Boolean {
            if (offset + HEADER_BYTES > length) return false
            val segments = buffer.u8(offset + 26)
            val table = offset + HEADER_BYTES
            if (table + segments > length) return false
            var payload = 0
            for (segment in 0 until segments) payload += buffer.u8(table + segment)
            return table + segments + payload <= length
        }

        private fun le32(buffer: ByteArray, offset: Int): Long =
            (buffer.u8(offset).toLong()) or (buffer.u8(offset + 1).toLong() shl 8) or
                (buffer.u8(offset + 2).toLong() shl 16) or (buffer.u8(offset + 3).toLong() shl 24)

        private fun crc(buffer: ByteArray, from: Int, to: Int): Long {
            var crc = 0
            for (i in from until to) {
                val value = if (i in from + 22 until from + 26) 0 else buffer.u8(i)
                crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor value) and 0xFF]
            }
            return crc.toLong() and 0xFFFFFFFFL
        }

        private val CRC_TABLE = IntArray(256) { index ->
            var value = index shl 24
            repeat(8) { value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
            value
        }
    }
}

internal object OggOpusSeek {
    const val PRE_ROLL_FRAMES = 3840L
    private const val WINDOW_FRAMES = 2L * 48000

    fun scanner(sid: Int, preSkip: Int) = SeekSearch.Scanner { buffer, length, eof, from ->
        var i = from
        while (i + 4 <= length) {
            if (!buffer.matches(i, "OggS")) {
                i++
                continue
            }
            if (!eof && !OggPageHeader.complete(buffer, i, length)) return@Scanner SeekSearch.Scan.Resume(i)
            val page = OggPageHeader.parse(buffer, i, length)
            if (page == null || page.sid != sid) {
                i++
                continue
            }
            val nextOffset = i + page.length
            if (!eof && !OggPageHeader.complete(buffer, nextOffset, length)) return@Scanner SeekSearch.Scan.Resume(i)
            val next = OggPageHeader.parse(buffer, nextOffset, length)
            if (next != null && next.sid == sid && !next.continued && page.hasGranule &&
                next.sequenceNumber == (page.sequenceNumber + 1) and 0xFFFFFFFFL
            ) {
                val rate = if (next.hasGranule && next.granulePosition > page.granulePosition) {
                    next.length.toDouble() / (next.granulePosition - page.granulePosition)
                } else Double.NaN
                return@Scanner SeekSearch.Scan.Found(nextOffset, page.granulePosition - preSkip, rate)
            }
            i = nextOffset
        }
        SeekSearch.Scan.Resume((length - 3).coerceAtLeast(from))
    }

    suspend fun land(
        source: AudioSource,
        sid: Int,
        preSkip: Int,
        targetFrame: Long,
        totalDuration: Long,
        scope: CoroutineScope
    ): SeekSearch.Landing? {
        val size = source.size()
        val totalFrames = totalDuration * 48
        val end = if (size > 0 && totalFrames > 0) SeekSearch.Point(size, totalFrames) else null
        val scanner = scanner(sid, preSkip)
        return SeekSearch.land(
            target = targetFrame - PRE_ROLL_FRAMES,
            window = WINDOW_FRAMES,
            start = SeekSearch.Point(0, 0),
            end = end,
            hint = null
        ) { SeekSearch.probe(source, it, scope, scanner) }
    }
}
