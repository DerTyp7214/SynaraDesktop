package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.OpusHead
import dev.dertyp.synara.player.audio.matches
import dev.dertyp.synara.player.audio.u8
import kotlin.math.min

internal object OggTail {
    const val TAIL_BYTES = 64 * 1024
    private const val HEADER_BYTES = 27
    private const val SAMPLE_RATE = 48000

    class StreamStart(val sid: Int, val preSkip: Int)

    fun streamStart(firstPage: ByteArray): StreamStart? {
        if (firstPage.size < HEADER_BYTES || !firstPage.matches(0, "OggS")) return null
        val segments = firstPage.u8(26)
        val packet = HEADER_BYTES + segments
        if (packet > firstPage.size) return null
        var payload = 0
        for (segment in 0 until segments) payload += firstPage.u8(HEADER_BYTES + segment)
        val head = OpusHead.parse(firstPage.copyOfRange(packet, min(packet + payload, firstPage.size))) ?: return null
        return StreamStart(sid(firstPage, 0), head.preSkip)
    }

    fun lastGranulePosition(tail: ByteArray, sid: Int): Long? {
        var offset = tail.size - HEADER_BYTES
        while (offset >= 0) {
            if (tail.matches(offset, "OggS") && tail.u8(offset + 4) == 0 && sid(tail, offset) == sid) {
                val segments = tail.u8(offset + 26)
                val table = offset + HEADER_BYTES
                if (table + segments <= tail.size) {
                    var payload = 0
                    for (segment in 0 until segments) payload += tail.u8(table + segment)
                    if (table + segments + payload == tail.size) return granulePosition(tail, offset)
                }
            }
            offset--
        }
        return null
    }

    fun durationMs(tail: ByteArray, start: StreamStart): Long? {
        val granule = lastGranulePosition(tail, start.sid) ?: return null
        val frames = OpusEndTrim.totalFrames(granule, start.preSkip) ?: return null
        return frames * 1000 / SAMPLE_RATE
    }

    private fun sid(page: ByteArray, offset: Int): Int =
        page.u8(offset + 14) or (page.u8(offset + 15) shl 8) or (page.u8(offset + 16) shl 16) or (page.u8(offset + 17) shl 24)

    private fun granulePosition(page: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or page.u8(offset + 6 + i).toLong()
        return value
    }
}
