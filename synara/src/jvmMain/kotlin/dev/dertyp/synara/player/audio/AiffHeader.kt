package dev.dertyp.synara.player.audio

import kotlin.math.pow
import kotlin.math.roundToInt

object AiffHeader {
    const val FORM = 0x464F524D // "FORM"
    const val AIFF = 0x41494646 // "AIFF"
    const val AIFC = 0x41494643 // "AIFC"
    const val COMM = 0x434F4D4D // "COMM"
    const val SSND = 0x53534E44 // "SSND"

    const val COMM_MIN_SIZE = 18

    const val CHUNK_HEADER_SIZE = 8

    const val SSND_HEADER_SIZE = 8

    enum class Compression(val id: String, val isBigEndian: Boolean, val isFloat: Boolean = false) {
        NONE("NONE", isBigEndian = true),
        TWOS("twos", isBigEndian = true),
        SOWT("sowt", isBigEndian = false),
        IN24("in24", isBigEndian = true),
        IN32("in32", isBigEndian = true),
        FL32("fl32", isBigEndian = true, isFloat = true),
        FL32_UPPER("FL32", isBigEndian = true, isFloat = true),
        RAW("raw ", isBigEndian = true);

        companion object {
            fun fromId(id: String): Compression? = entries.firstOrNull { it.id == id }
        }
    }

    data class Comm(
        val channelCount: Int,
        val sampleFrames: Long,
        val sampleSizeBits: Int,
        val sampleRate: Int,
        val compression: Compression,
    ) {
        val bytesPerSample: Int get() = (sampleSizeBits + 7) / 8
        val bytesPerFrame: Int get() = bytesPerSample * channelCount
        val bytesPerSecond: Long get() = bytesPerFrame.toLong() * sampleRate
        val durationUs: Long get() = if (sampleRate <= 0) 0 else sampleFrames * 1_000_000L / sampleRate

        val isSupported: Boolean
            get() = channelCount in 1..8 && sampleRate > 0 && when {
                compression.isFloat -> sampleSizeBits == 32
                compression == Compression.SOWT -> sampleSizeBits == 16
                else -> sampleSizeBits in 1..32
            }
    }

    fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    fun readUnsignedInt(bytes: ByteArray, offset: Int): Long = readInt(bytes, offset).toLong() and 0xFFFFFFFFL

    fun isAiff(header: ByteArray, offset: Int = 0): Boolean {
        if (header.size - offset < 12) return false
        if (readInt(header, offset) != FORM) return false
        val formType = readInt(header, offset + 8)
        return formType == AIFF || formType == AIFC
    }

    fun decodeExtended(bytes: ByteArray, offset: Int): Int {
        val signExp = readShort(bytes, offset)
        val exponent = signExp and 0x7FFF
        val hiMantissa = readUnsignedInt(bytes, offset + 2)
        val loMantissa = readUnsignedInt(bytes, offset + 6)
        if (exponent == 0 && hiMantissa == 0L && loMantissa == 0L) return 0
        if (exponent == 0x7FFF) return 0 // Inf / NaN
        val value = hiMantissa.toDouble() * 2.0.pow(exponent - 16383 - 31) +
            loMantissa.toDouble() * 2.0.pow(exponent - 16383 - 63)
        val signed = if (signExp and 0x8000 != 0) -value else value
        return signed.roundToInt()
    }

    fun parseComm(payload: ByteArray, size: Int, isAifc: Boolean): Comm? {
        if (size < COMM_MIN_SIZE || payload.size < size) return null
        val channels = readShort(payload, 0)
        val frames = readUnsignedInt(payload, 2)
        val sampleSize = readShort(payload, 6)
        val sampleRate = decodeExtended(payload, 8)
        val compression = if (isAifc && size >= COMM_MIN_SIZE + 4) {
            val id = String(payload, COMM_MIN_SIZE, 4, Charsets.US_ASCII)
            Compression.fromId(id) ?: return null
        } else {
            Compression.NONE
        }
        return Comm(channels, frames, sampleSize, sampleRate, compression)
    }

    fun toLittleEndian(buffer: ByteArray, length: Int, bytesPerSample: Int, compression: Compression) {
        when {
            bytesPerSample == 1 -> {
                if (compression != Compression.RAW) {
                    for (i in 0 until length) buffer[i] = (buffer[i] + 128).toByte()
                }
            }
            !compression.isBigEndian -> Unit
            bytesPerSample == 2 -> {
                var i = 0
                while (i + 1 < length) {
                    val t = buffer[i]; buffer[i] = buffer[i + 1]; buffer[i + 1] = t
                    i += 2
                }
            }
            bytesPerSample == 3 -> {
                var i = 0
                while (i + 2 < length) {
                    val t = buffer[i]; buffer[i] = buffer[i + 2]; buffer[i + 2] = t
                    i += 3
                }
            }
            bytesPerSample == 4 -> {
                var i = 0
                while (i + 3 < length) {
                    var t = buffer[i]; buffer[i] = buffer[i + 3]; buffer[i + 3] = t
                    t = buffer[i + 1]; buffer[i + 1] = buffer[i + 2]; buffer[i + 2] = t
                    i += 4
                }
            }
        }
    }
}
