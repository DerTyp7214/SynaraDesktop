package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.matches
import dev.dertyp.synara.player.audio.u16
import dev.dertyp.synara.player.audio.u24
import dev.dertyp.synara.player.audio.u64
import dev.dertyp.synara.player.audio.u8
import kotlinx.coroutines.CoroutineScope

internal class FlacStreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val maxFrameSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val seekPoints: List<SeekSearch.Point>
) {
    companion object {
        private const val STREAMINFO = 0
        private const val SEEKTABLE = 3
        private const val PLACEHOLDER = -1L

        fun parse(metadata: ByteArray): FlacStreamInfo? {
            if (!metadata.matches(0, "fLaC")) return null
            var position = 4
            var info: FlacStreamInfo? = null
            val points = mutableListOf<SeekSearch.Point>()
            while (position + 4 <= metadata.size) {
                val type = metadata.u8(position) and 0x7F
                val last = metadata.u8(position) and 0x80 != 0
                val length = metadata.u24(position + 1)
                val body = position + 4
                if (body + length > metadata.size) break
                if (type == STREAMINFO && length >= 18) {
                    info = FlacStreamInfo(
                        minBlockSize = metadata.u16(body),
                        maxBlockSize = metadata.u16(body + 2),
                        maxFrameSize = metadata.u24(body + 7),
                        sampleRate = (metadata.u8(body + 10) shl 12) or (metadata.u8(body + 11) shl 4) or (metadata.u8(body + 12) shr 4),
                        channels = ((metadata.u8(body + 12) shr 1) and 0x07) + 1,
                        bitsPerSample = (((metadata.u8(body + 12) and 0x01) shl 4) or (metadata.u8(body + 13) shr 4)) + 1,
                        totalSamples = ((metadata.u8(body + 13) and 0x0F).toLong() shl 32) or
                            (metadata.u24(body + 14).toLong() shl 8) or metadata.u8(body + 17).toLong(),
                        seekPoints = emptyList()
                    )
                } else if (type == SEEKTABLE) {
                    var point = body
                    while (point + 18 <= body + length) {
                        val sample = metadata.u64(point)
                        val offset = metadata.u64(point + 8)
                        if (sample != PLACEHOLDER && sample >= 0 && offset >= 0) points += SeekSearch.Point(offset, sample)
                        point += 18
                    }
                }
                position = body + length
                if (last) break
            }
            val found = info ?: return null
            if (found.sampleRate <= 0) return null
            return FlacStreamInfo(
                found.minBlockSize, found.maxBlockSize, found.maxFrameSize, found.sampleRate,
                found.channels, found.bitsPerSample, found.totalSamples,
                points.sortedBy { it.position }
            )
        }
    }
}

internal class FlacFrameHeader(val length: Int, val variable: Boolean, val sampleNumber: Long, val blockSize: Int) {
    companion object {
        const val MAX_LENGTH = 16

        fun parse(buffer: ByteArray, offset: Int, length: Int, info: FlacStreamInfo): FlacFrameHeader? {
            if (offset + 4 > length) return null
            if (buffer.u8(offset) != 0xFF || (buffer.u8(offset + 1) and 0xFE) != 0xF8) return null
            val variable = buffer.u8(offset + 1) and 0x01 != 0
            val blockCode = buffer.u8(offset + 2) shr 4
            val rateCode = buffer.u8(offset + 2) and 0x0F
            val channelCode = buffer.u8(offset + 3) shr 4
            val sizeCode = (buffer.u8(offset + 3) shr 1) and 0x07
            if (blockCode == 0 || rateCode == 15 || channelCode > 10 || sizeCode == 3 || buffer.u8(offset + 3) and 0x01 != 0) return null
            val channels = if (channelCode < 8) channelCode + 1 else 2
            if (channels != info.channels) return null
            val bits = SAMPLE_SIZES[sizeCode]
            if (bits != 0 && bits != info.bitsPerSample) return null

            var position = offset + 4
            if (position >= length) return null
            val first = buffer.u8(position++)
            val extra = when {
                first and 0x80 == 0 -> 0
                first and 0xE0 == 0xC0 -> 1
                first and 0xF0 == 0xE0 -> 2
                first and 0xF8 == 0xF0 -> 3
                first and 0xFC == 0xF8 -> 4
                first and 0xFE == 0xFC -> 5
                first == 0xFE && variable -> 6
                else -> return null
            }
            var number = if (extra == 0) first.toLong() else (first and (0x3F shr extra)).toLong()
            repeat(extra) {
                if (position >= length) return null
                val next = buffer.u8(position++)
                if (next and 0xC0 != 0x80) return null
                number = (number shl 6) or (next and 0x3F).toLong()
            }

            val blockSize = when (blockCode) {
                1 -> 192
                in 2..5 -> 576 shl (blockCode - 2)
                6 -> {
                    if (position + 1 > length) return null
                    buffer.u8(position++) + 1
                }
                7 -> {
                    if (position + 2 > length) return null
                    val value = buffer.u16(position) + 1
                    position += 2
                    value
                }
                else -> 256 shl (blockCode - 8)
            }
            val sampleRate = when (rateCode) {
                0 -> info.sampleRate
                12 -> {
                    if (position + 1 > length) return null
                    buffer.u8(position++) * 1000
                }
                13, 14 -> {
                    if (position + 2 > length) return null
                    val value = buffer.u16(position) * if (rateCode == 14) 10 else 1
                    position += 2
                    value
                }
                else -> SAMPLE_RATES[rateCode]
            }
            if (sampleRate != info.sampleRate) return null
            if (info.maxBlockSize > 0 && blockSize > info.maxBlockSize) return null

            if (position + 2 > length) return null
            if (crc8(buffer, offset, position) != buffer.u8(position)) return null
            position++
            val subframe = buffer.u8(position)
            if (subframe and 0x80 != 0 || !validSubframeType((subframe shr 1) and 0x3F)) return null

            val sampleNumber = if (variable) {
                number
            } else {
                val fixed = if (info.minBlockSize > 0 && info.minBlockSize == info.maxBlockSize) info.minBlockSize else blockSize
                number * fixed
            }
            if (info.totalSamples > 0 && sampleNumber >= info.totalSamples) return null
            return FlacFrameHeader(position - offset, variable, sampleNumber, blockSize)
        }

        private fun validSubframeType(type: Int): Boolean = when {
            type <= 1 -> true
            type < 8 -> false
            type < 16 -> type and 0x07 <= 4
            else -> type >= 32
        }

        private fun crc8(buffer: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor buffer.u8(i)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc
        }

        private val SAMPLE_SIZES = intArrayOf(0, 8, 12, 0, 16, 20, 24, 32)
        private val SAMPLE_RATES = intArrayOf(0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000)
    }
}

internal object FlacSeek {
    private const val WINDOW_MS = 2_000L

    fun scanner(info: FlacStreamInfo) = SeekSearch.Scanner { buffer, length, eof, from ->
        var i = from
        while (i < length - 1) {
            if (buffer[i] != 0xFF.toByte()) {
                i++
                continue
            }
            if (!eof && i + FlacFrameHeader.MAX_LENGTH + 2 > length) return@Scanner SeekSearch.Scan.Resume(i)
            val header = FlacFrameHeader.parse(buffer, i, length, info)
            if (header != null) {
                when (val next = nextFrame(buffer, i, length, header, info)) {
                    REJECTED -> {}
                    MORE -> {
                        if (!eof) return@Scanner SeekSearch.Scan.Resume(i)
                        if (crc16(buffer, i, length) == 0 || header.sampleNumber + header.blockSize == info.totalSamples) {
                            return@Scanner SeekSearch.Scan.Found(i, header.sampleNumber, Double.NaN)
                        }
                    }
                    else -> return@Scanner SeekSearch.Scan.Found(i, header.sampleNumber, (next - i).toDouble() / header.blockSize)
                }
            }
            i++
        }
        SeekSearch.Scan.Resume((length - 1).coerceAtLeast(from))
    }

    private const val REJECTED = -1
    private const val MORE = -2

    private fun nextFrame(buffer: ByteArray, offset: Int, length: Int, header: FlacFrameHeader, info: FlacStreamInfo): Int {
        val maxFrameSize = if (info.maxFrameSize > 0) {
            info.maxFrameSize
        } else {
            header.blockSize * info.channels * (info.bitsPerSample + 1) / 8 + 1024
        }
        val limit = offset + maxFrameSize + 1
        for (j in offset + header.length + 1 until minOf(limit, length) - 1) {
            if (buffer[j] != 0xFF.toByte()) continue
            val next = FlacFrameHeader.parse(buffer, j, length, info) ?: continue
            if (next.variable == header.variable && next.sampleNumber == header.sampleNumber + header.blockSize &&
                crc16(buffer, offset, j) == 0
            ) return j
        }
        return if (limit + FlacFrameHeader.MAX_LENGTH + 2 <= length) REJECTED else MORE
    }

    private fun crc16(buffer: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) crc = ((crc shl 8) xor CRC16_TABLE[(crc shr 8) xor buffer.u8(i)]) and 0xFFFF
        return crc
    }

    private val CRC16_TABLE = IntArray(256) { index ->
        var value = index shl 8
        repeat(8) { value = if (value and 0x8000 != 0) ((value shl 1) xor 0x8005) and 0xFFFF else (value shl 1) and 0xFFFF }
        value
    }

    suspend fun land(
        source: AudioSource,
        metadata: ByteArray,
        info: FlacStreamInfo,
        targetSample: Long,
        totalDuration: Long,
        scope: CoroutineScope
    ): SeekSearch.Landing? {
        val audioStart = metadata.size.toLong()
        val size = source.size()
        val totalSamples = if (info.totalSamples > 0) info.totalSamples else totalDuration * info.sampleRate / 1000
        val end = if (size > audioStart && totalSamples > 0) SeekSearch.Point(size, totalSamples) else null
        val points = info.seekPoints.map { SeekSearch.Point(audioStart + it.offset, it.position) }
            .filter { end == null || it.offset < end.offset }
        val hint = points.lastOrNull { it.position <= targetSample }
        val upper = points.firstOrNull { it.position > targetSample } ?: end
        val scanner = scanner(info)
        return SeekSearch.land(
            target = targetSample,
            window = WINDOW_MS * info.sampleRate / 1000,
            start = SeekSearch.Point(audioStart, 0),
            end = upper,
            hint = hint
        ) { SeekSearch.probe(source, it, scope, scanner) }
    }
}
