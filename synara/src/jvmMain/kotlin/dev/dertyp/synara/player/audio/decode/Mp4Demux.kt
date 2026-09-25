package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.UnsupportedAudioFormatException
import dev.dertyp.synara.player.audio.fourCc
import dev.dertyp.synara.player.audio.readExactly
import dev.dertyp.synara.player.audio.skipExactly
import dev.dertyp.synara.player.audio.u16
import dev.dertyp.synara.player.audio.u32
import dev.dertyp.synara.player.audio.u64
import dev.dertyp.synara.player.audio.u8
import java.io.IOException
import java.io.InputStream

class Mp4Track(
    val timescale: Long,
    val durationUnits: Long,
    val sampleRate: Int,
    val channels: Int,
    val objectType: Int,
    val audioSpecificConfig: ByteArray,
    val offsets: LongArray,
    val sizes: IntArray,
    val times: LongArray,
) {
    val sampleCount: Int get() = sizes.size

    val durationMs: Long
        get() {
            val units = if (durationUnits > 0) durationUnits else endTime()
            return if (timescale > 0) units * 1000 / timescale else 0L
        }

    val bitRate: Long
        get() {
            val ms = durationMs
            if (ms <= 0) return 0L
            var total = 0L
            for (size in sizes) total += size
            return total * 8 * 1000 / ms
        }

    fun sampleAt(timeUnits: Long): Int {
        if (times.isEmpty()) return 0
        var low = 0
        var high = times.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (times[mid] <= timeUnits) low = mid else high = mid - 1
        }
        return low
    }

    private fun endTime(): Long {
        if (times.isEmpty()) return 0L
        val last = times.size - 1
        val delta = if (last > 0) times[last] - times[last - 1] else 0L
        return times[last] + delta
    }
}

object Mp4Demux {
    private const val MAX_MOOV_BYTES = 128L * 1024 * 1024
    private const val MAX_INLINE_SKIP = 512L * 1024
    private val AAC_OBJECT_TYPES = setOf(0x40, 0x66, 0x67, 0x68)

    suspend fun read(
        input: InputStream,
        startOffset: Long,
        reopen: suspend (Long) -> InputStream?
    ): Mp4Track {
        var stream = input
        var position = startOffset
        val header = ByteArray(16)
        try {
            while (true) {
                if (readExactly(stream, header, 8) < 8) throw UnsupportedAudioFormatException("mp4 without moov")
                var size = header.u32(0)
                val type = header.fourCc(4)
                var headerLength = 8
                if (size == 1L) {
                    if (readExactly(stream, header, 8, 8) < 8) throw UnsupportedAudioFormatException("mp4 without moov")
                    size = header.u64(8)
                    headerLength = 16
                }
                val payloadSize = if (size == 0L) -1L else size - headerLength
                if (size != 0L && size < headerLength) throw IOException("Invalid mp4 atom $type")

                when (type) {
                    "moov" -> {
                        if (payloadSize < 0 || payloadSize > MAX_MOOV_BYTES) throw IOException("Invalid moov size $payloadSize")
                        val payload = ByteArray(payloadSize.toInt())
                        if (readExactly(stream, payload) < payload.size) throw IOException("Truncated moov")
                        return parseMoov(payload)
                    }
                    "moof" -> throw UnsupportedAudioFormatException("fragmented mp4")
                    else -> {
                        if (payloadSize < 0) throw UnsupportedAudioFormatException("mp4 without moov")
                        val next = position + size
                        if (payloadSize <= MAX_INLINE_SKIP) {
                            if (!skipExactly(stream, payloadSize)) throw UnsupportedAudioFormatException("mp4 without moov")
                        } else {
                            stream.close()
                            stream = reopen(next) ?: throw IOException("Unable to reopen mp4 at $next")
                        }
                        position = next
                    }
                }
            }
        } finally {
            if (stream !== input) stream.close()
        }
    }

    fun parseMoov(data: ByteArray): Mp4Track {
        var fragmented = false
        var track: Mp4Track? = null
        forEachBox(data, 0, data.size) { type, start, end ->
            when (type) {
                "mvex" -> fragmented = true
                "trak" -> if (track == null) track = parseTrak(data, start, end)
            }
        }
        val found = track ?: throw UnsupportedAudioFormatException("mp4 without audio track")
        if (found.sampleCount == 0) {
            throw UnsupportedAudioFormatException(if (fragmented) "fragmented mp4" else "mp4 without samples")
        }
        return found
    }

    private class StblTables {
        var sampleRate = 0
        var channels = 0
        var objectType = -1
        var sampleEntry: String? = null
        var asc: ByteArray? = null
        var sttsCounts = IntArray(0)
        var sttsDeltas = LongArray(0)
        var stscFirst = IntArray(0)
        var stscSamples = IntArray(0)
        var sizes: IntArray? = null
        var chunkOffsets: LongArray? = null
    }

    private fun parseTrak(data: ByteArray, start: Int, end: Int): Mp4Track? {
        val mdia = findBox(data, start, end, "mdia") ?: return null
        val hdlr = findBox(data, mdia.first, mdia.second, "hdlr") ?: return null
        if (hdlr.first + 12 > hdlr.second || data.fourCc(hdlr.first + 8) != "soun") return null
        val mdhd = findBox(data, mdia.first, mdia.second, "mdhd") ?: return null
        val version = data.u8(mdhd.first)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            timescale = data.u32(mdhd.first + 20)
            duration = data.u64(mdhd.first + 24)
        } else {
            timescale = data.u32(mdhd.first + 12)
            duration = data.u32(mdhd.first + 16)
        }
        val minf = findBox(data, mdia.first, mdia.second, "minf") ?: return null
        val stbl = findBox(data, minf.first, minf.second, "stbl") ?: return null
        val tables = StblTables()
        forEachBox(data, stbl.first, stbl.second) { type, s, e ->
            when (type) {
                "stsd" -> parseStsd(data, s, e, tables)
                "stts" -> {
                    val count = data.u32(s + 4).toInt()
                    tables.sttsCounts = IntArray(count) { data.u32(s + 8 + it * 8).toInt() }
                    tables.sttsDeltas = LongArray(count) { data.u32(s + 12 + it * 8) }
                }
                "stsc" -> {
                    val count = data.u32(s + 4).toInt()
                    tables.stscFirst = IntArray(count) { data.u32(s + 8 + it * 12).toInt() }
                    tables.stscSamples = IntArray(count) { data.u32(s + 12 + it * 12).toInt() }
                }
                "stsz" -> {
                    val fixed = data.u32(s + 4).toInt()
                    val count = data.u32(s + 8).toInt()
                    tables.sizes = if (fixed != 0) IntArray(count) { fixed }
                    else IntArray(count) { data.u32(s + 12 + it * 4).toInt() }
                }
                "stz2" -> {
                    val fieldSize = data.u8(s + 7)
                    val count = data.u32(s + 8).toInt()
                    tables.sizes = IntArray(count) { index ->
                        when (fieldSize) {
                            4 -> {
                                val b = data.u8(s + 12 + index / 2)
                                if (index % 2 == 0) b shr 4 else b and 0x0F
                            }
                            8 -> data.u8(s + 12 + index)
                            else -> data.u16(s + 12 + index * 2)
                        }
                    }
                }
                "stco" -> {
                    val count = data.u32(s + 4).toInt()
                    tables.chunkOffsets = LongArray(count) { data.u32(s + 8 + it * 4) }
                }
                "co64" -> {
                    val count = data.u32(s + 4).toInt()
                    tables.chunkOffsets = LongArray(count) { data.u64(s + 8 + it * 8) }
                }
            }
        }

        val entry = tables.sampleEntry
        if (entry != "mp4a") throw UnsupportedAudioFormatException("mp4 audio $entry")
        if (tables.objectType !in AAC_OBJECT_TYPES) throw UnsupportedAudioFormatException("mp4 object type ${tables.objectType}")
        val asc = tables.asc ?: throw UnsupportedAudioFormatException("mp4 aac without config")
        val sizes = tables.sizes ?: IntArray(0)
        val chunkOffsets = tables.chunkOffsets ?: LongArray(0)

        val offsets = LongArray(sizes.size)
        var sample = 0
        for (i in tables.stscFirst.indices) {
            val firstChunk = tables.stscFirst[i] - 1
            val lastChunk = if (i + 1 < tables.stscFirst.size) tables.stscFirst[i + 1] - 1 else chunkOffsets.size
            for (chunk in firstChunk until minOf(lastChunk, chunkOffsets.size)) {
                var offset = chunkOffsets[chunk]
                repeat(tables.stscSamples[i]) {
                    if (sample < sizes.size) {
                        offsets[sample] = offset
                        offset += sizes[sample]
                        sample++
                    }
                }
            }
        }
        val sampleCount = minOf(sample, sizes.size)

        val times = LongArray(sampleCount)
        var time = 0L
        var index = 0
        var lastDelta = 1024L
        for (i in tables.sttsCounts.indices) {
            lastDelta = tables.sttsDeltas[i]
            repeat(tables.sttsCounts[i]) {
                if (index < sampleCount) {
                    times[index++] = time
                    time += lastDelta
                }
            }
        }
        while (index < sampleCount) {
            times[index++] = time
            time += lastDelta
        }

        return Mp4Track(
            timescale = timescale,
            durationUnits = duration,
            sampleRate = tables.sampleRate,
            channels = tables.channels,
            objectType = tables.objectType,
            audioSpecificConfig = asc,
            offsets = if (sampleCount == offsets.size) offsets else offsets.copyOf(sampleCount),
            sizes = if (sampleCount == sizes.size) sizes else sizes.copyOf(sampleCount),
            times = times,
        )
    }

    private fun parseStsd(data: ByteArray, start: Int, end: Int, tables: StblTables) {
        val entryStart = start + 8
        if (entryStart + 8 > end) return
        val entrySize = data.u32(entryStart).toInt()
        val type = data.fourCc(entryStart + 4)
        tables.sampleEntry = type
        if (type != "mp4a") return
        val entryEnd = minOf(end, entryStart + entrySize)
        val body = entryStart + 8
        if (body + 28 > entryEnd) return
        val version = data.u16(body + 8)
        tables.channels = data.u16(body + 16)
        tables.sampleRate = (data.u32(body + 24) ushr 16).toInt()
        val childrenStart = body + 28 + when (version) {
            1 -> 16
            2 -> 36
            else -> 0
        }
        val esds = findBox(data, childrenStart, entryEnd, "esds")
            ?: findBox(data, childrenStart, entryEnd, "wave")?.let { findBox(data, it.first, it.second, "esds") }
            ?: return
        parseEsds(data, esds.first, esds.second, tables)
    }

    private fun parseEsds(data: ByteArray, start: Int, end: Int, tables: StblTables) {
        var p = start + 4
        while (p < end) {
            val tag = data.u8(p++)
            var length = 0
            var count = 0
            while (p < end && count < 4) {
                val b = data.u8(p++)
                length = (length shl 7) or (b and 0x7F)
                count++
                if (b and 0x80 == 0) break
            }
            when (tag) {
                0x03 -> {
                    p += 2
                    val flags = data.u8(p++)
                    if (flags and 0x80 != 0) p += 2
                    if (flags and 0x40 != 0) p += 1 + data.u8(p)
                    if (flags and 0x20 != 0) p += 2
                }
                0x04 -> {
                    tables.objectType = data.u8(p)
                    p += 13
                }
                0x05 -> {
                    if (p + length <= end) tables.asc = data.copyOfRange(p, p + length)
                    return
                }
                else -> p += length
            }
        }
    }

    private fun findBox(data: ByteArray, start: Int, end: Int, wanted: String): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        forEachBox(data, start, end) { type, s, e ->
            if (result == null && type == wanted) result = s to e
        }
        return result
    }

    private inline fun forEachBox(data: ByteArray, start: Int, end: Int, block: (String, Int, Int) -> Unit) {
        var p = start
        while (p + 8 <= end) {
            var size = data.u32(p)
            val type = data.fourCc(p + 4)
            var headerLength = 8
            if (size == 1L) {
                if (p + 16 > end) return
                size = data.u64(p + 8)
                headerLength = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < headerLength || p + size > end) return
            block(type, p + headerLength, (p + size).toInt())
            p += size.toInt()
        }
    }
}
