package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.u8

data class AdtsHeader(
    val profile: Int,
    val sampleRateIndex: Int,
    val channelConfig: Int,
    val frameLength: Int,
    val protectionAbsent: Boolean,
) {
    val headerLength: Int get() = if (protectionAbsent) 7 else 9
    val sampleRate: Int get() = SAMPLE_RATES[sampleRateIndex]

    fun audioSpecificConfig(): ByteArray {
        val objectType = profile + 1
        val value = (objectType shl 11) or (sampleRateIndex shl 7) or (channelConfig shl 3)
        return byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    fun isCompatible(other: AdtsHeader): Boolean =
        profile == other.profile && sampleRateIndex == other.sampleRateIndex && channelConfig == other.channelConfig

    companion object {
        const val SAMPLES_PER_FRAME = 1024

        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun parse(bytes: ByteArray, offset: Int): AdtsHeader? {
            if (offset < 0 || offset + 7 > bytes.size) return null
            if (bytes.u8(offset) != 0xFF || (bytes.u8(offset + 1) and 0xF6) != 0xF0) return null
            val protectionAbsent = bytes.u8(offset + 1) and 0x01 == 1
            val b2 = bytes.u8(offset + 2)
            val profile = (b2 shr 6) and 0x03
            val sampleRateIndex = (b2 shr 2) and 0x0F
            if (sampleRateIndex >= SAMPLE_RATES.size) return null
            val channelConfig = ((b2 and 0x01) shl 2) or ((bytes.u8(offset + 3) shr 6) and 0x03)
            val frameLength = ((bytes.u8(offset + 3) and 0x03) shl 11) or
                    (bytes.u8(offset + 4) shl 3) or
                    ((bytes.u8(offset + 5) shr 5) and 0x07)
            val header = AdtsHeader(profile, sampleRateIndex, channelConfig, frameLength, protectionAbsent)
            if (frameLength <= header.headerLength) return null
            return header
        }

        fun parseConfirmed(bytes: ByteArray, offset: Int, length: Int = bytes.size, reference: AdtsHeader? = null): AdtsHeader? {
            val header = parse(bytes, offset) ?: return null
            if (reference != null && !header.isCompatible(reference)) return null
            val next = offset + header.frameLength
            if (next + 7 > length) return null
            val following = parse(bytes, next) ?: return null
            return if (following.isCompatible(header)) header else null
        }
    }
}
