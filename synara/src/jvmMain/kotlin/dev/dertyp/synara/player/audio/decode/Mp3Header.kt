package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.matches
import dev.dertyp.synara.player.audio.u16
import dev.dertyp.synara.player.audio.u32
import dev.dertyp.synara.player.audio.u8

data class Mp3Header(
    val version: Version,
    val layer: Int,
    val bitRateKbps: Int,
    val sampleRate: Int,
    val padding: Boolean,
    val channelMode: Int,
) {
    enum class Version { MPEG1, MPEG2, MPEG25 }

    val channels: Int get() = if (channelMode == MODE_MONO) 1 else 2

    val samplesPerFrame: Int
        get() = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (version == Version.MPEG1) 1152 else 576
        }

    val frameLength: Int
        get() = if (layer == 1) {
            (12 * bitRateKbps * 1000 / sampleRate + (if (padding) 1 else 0)) * 4
        } else {
            samplesPerFrame / 8 * bitRateKbps * 1000 / sampleRate + (if (padding) 1 else 0)
        }

    val sideInfoLength: Int
        get() = if (version == Version.MPEG1) {
            if (channels == 1) 17 else 32
        } else {
            if (channels == 1) 9 else 17
        }

    fun isCompatible(other: Mp3Header): Boolean =
        version == other.version && layer == other.layer && sampleRate == other.sampleRate

    companion object {
        const val MODE_MONO = 3

        private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000)

        fun parse(bytes: ByteArray, offset: Int): Mp3Header? {
            if (offset < 0 || offset + 4 > bytes.size) return null
            val b0 = bytes.u8(offset)
            val b1 = bytes.u8(offset + 1)
            val b2 = bytes.u8(offset + 2)
            val b3 = bytes.u8(offset + 3)
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val version = when ((b1 shr 3) and 0x03) {
                0 -> Version.MPEG25
                2 -> Version.MPEG2
                3 -> Version.MPEG1
                else -> return null
            }
            val layer = when ((b1 shr 1) and 0x03) {
                1 -> 3
                2 -> 2
                3 -> 1
                else -> return null
            }
            val bitRateIndex = (b2 shr 4) and 0x0F
            if (bitRateIndex == 0 || bitRateIndex == 15) return null
            val sampleRateIndex = (b2 shr 2) and 0x03
            if (sampleRateIndex == 3) return null
            val table = when (version) {
                Version.MPEG1 -> when (layer) {
                    1 -> BITRATES_V1_L1
                    2 -> BITRATES_V1_L2
                    else -> BITRATES_V1_L3
                }
                else -> if (layer == 1) BITRATES_V2_L1 else BITRATES_V2_L23
            }
            val baseRate = SAMPLE_RATES_V1[sampleRateIndex]
            val sampleRate = when (version) {
                Version.MPEG1 -> baseRate
                Version.MPEG2 -> baseRate / 2
                Version.MPEG25 -> baseRate / 4
            }
            return Mp3Header(
                version = version,
                layer = layer,
                bitRateKbps = table[bitRateIndex],
                sampleRate = sampleRate,
                padding = (b2 shr 1) and 0x01 == 1,
                channelMode = (b3 shr 6) and 0x03,
            )
        }

        fun parseConfirmed(bytes: ByteArray, offset: Int, length: Int = bytes.size, reference: Mp3Header? = null): Mp3Header? {
            val header = parse(bytes, offset) ?: return null
            if (reference != null && !header.isCompatible(reference)) return null
            val next = offset + header.frameLength
            if (next + 4 > length) return null
            val following = parse(bytes, next) ?: return null
            return if (following.isCompatible(header)) header else null
        }
    }
}

data class XingInfo(
    val frames: Long?,
    val bytes: Long?,
    val toc: IntArray?,
) {
    fun durationMs(header: Mp3Header): Long? =
        frames?.takeIf { it > 0 }?.let { it * header.samplesPerFrame * 1000L / header.sampleRate }

    fun seekOffset(timeMs: Long, durationMs: Long, dataSize: Long): Long? {
        val table = toc ?: return null
        if (durationMs <= 0 || dataSize <= 0) return null
        val percent = (timeMs.toDouble() * 100.0 / durationMs).coerceIn(0.0, 100.0)
        val index = percent.toInt().coerceAtMost(99)
        val fa = table[index].toDouble()
        val fb = if (index < 99) table[index + 1].toDouble() else 256.0
        val fx = fa + (fb - fa) * (percent - index)
        return (fx / 256.0 * dataSize).toLong()
    }

    override fun equals(other: Any?): Boolean =
        other is XingInfo && frames == other.frames && bytes == other.bytes &&
                (toc?.contentEquals(other.toc) ?: (other.toc == null))

    override fun hashCode(): Int = 31 * (31 * frames.hashCode() + bytes.hashCode()) + (toc?.contentHashCode() ?: 0)

    companion object {
        fun parse(frame: ByteArray, offset: Int, header: Mp3Header): XingInfo? {
            val start = offset + 4 + header.sideInfoLength
            if (!frame.matches(start, "Xing") && !frame.matches(start, "Info")) return null
            if (start + 8 > frame.size) return null
            val flags = frame.u32(start + 4).toInt()
            var position = start + 8
            var frames: Long? = null
            var bytes: Long? = null
            var toc: IntArray? = null
            if (flags and 0x01 != 0) {
                if (position + 4 > frame.size) return null
                frames = frame.u32(position)
                position += 4
            }
            if (flags and 0x02 != 0) {
                if (position + 4 > frame.size) return null
                bytes = frame.u32(position)
                position += 4
            }
            if (flags and 0x04 != 0 && position + 100 <= frame.size) {
                toc = IntArray(100) { frame.u8(position + it) }
            }
            return XingInfo(frames, bytes, toc)
        }
    }
}

data class VbriInfo(
    val bytes: Long,
    val frames: Long,
    val framesPerEntry: Int,
    val entries: LongArray,
) {
    fun durationMs(header: Mp3Header): Long? =
        frames.takeIf { it > 0 }?.let { it * header.samplesPerFrame * 1000L / header.sampleRate }

    fun seekOffset(timeMs: Long, header: Mp3Header): Long? {
        if (entries.isEmpty() || framesPerEntry <= 0) return null
        val entryMs = framesPerEntry.toDouble() * header.samplesPerFrame * 1000.0 / header.sampleRate
        if (entryMs <= 0) return null
        var offset = 0L
        var time = 0.0
        for (size in entries) {
            if (time + entryMs > timeMs) {
                return offset + ((timeMs - time) / entryMs * size).toLong()
            }
            time += entryMs
            offset += size
        }
        return offset
    }

    override fun equals(other: Any?): Boolean =
        other is VbriInfo && bytes == other.bytes && frames == other.frames &&
                framesPerEntry == other.framesPerEntry && entries.contentEquals(other.entries)

    override fun hashCode(): Int = 31 * (31 * bytes.hashCode() + frames.hashCode()) + entries.contentHashCode()

    companion object {
        private const val VBRI_OFFSET = 36

        fun parse(frame: ByteArray, offset: Int): VbriInfo? {
            val start = offset + VBRI_OFFSET
            if (!frame.matches(start, "VBRI") || start + 26 > frame.size) return null
            val bytes = frame.u32(start + 10)
            val frames = frame.u32(start + 14)
            val entryCount = frame.u16(start + 18)
            val scale = frame.u16(start + 20)
            val entrySize = frame.u16(start + 22)
            val framesPerEntry = frame.u16(start + 24)
            if (entrySize !in 1..4) return null
            val tableStart = start + 26
            if (tableStart + entryCount * entrySize > frame.size) return VbriInfo(bytes, frames, framesPerEntry, LongArray(0))
            val entries = LongArray(entryCount) { index ->
                var value = 0L
                for (i in 0 until entrySize) value = (value shl 8) or frame.u8(tableStart + index * entrySize + i).toLong()
                value * scale
            }
            return VbriInfo(bytes, frames, framesPerEntry, entries)
        }
    }
}
