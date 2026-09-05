package dev.dertyp.synara.player.audio

class OpusHead(
    val channels: Int,
    val preSkip: Int,
    val inputSampleRate: Int,
    val outputGain: Int,
    val mappingFamily: Int,
    val streamCount: Int,
    val coupledStreamCount: Int,
    val channelMapping: ShortArray
) {
    val isMultistream: Boolean get() = mappingFamily != 0

    val nativeChannelOrder: IntArray?
        get() = if (mappingFamily == 1) VORBIS_TO_NATIVE[channels] else null

    companion object {
        private val MAGIC = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte()
        )

        private const val SILENT_CHANNEL = 255

        private val VORBIS_TO_NATIVE: Map<Int, IntArray> = mapOf(
            3 to intArrayOf(0, 2, 1),
            5 to intArrayOf(0, 2, 1, 3, 4),
            6 to intArrayOf(0, 2, 1, 5, 3, 4),
            7 to intArrayOf(0, 2, 1, 6, 5, 3, 4),
            8 to intArrayOf(0, 2, 1, 7, 5, 6, 3, 4)
        )

        fun parse(data: ByteArray?): OpusHead? {
            if (data == null || data.size < 19) return null
            for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null

            val version = data[8].toInt() and 0xFF
            if ((version and 0xF0) != 0) return null

            val channels = data[9].toInt() and 0xFF
            if (channels < 1) return null

            val preSkip = readShortLe(data, 10)
            val inputSampleRate = readIntLe(data, 12)
            val outputGain = readShortLe(data, 16).toShort().toInt()
            val mappingFamily = data[18].toInt() and 0xFF

            if (mappingFamily == 0) {
                if (channels > 2) return null
                return OpusHead(
                    channels = channels,
                    preSkip = preSkip,
                    inputSampleRate = inputSampleRate,
                    outputGain = outputGain,
                    mappingFamily = 0,
                    streamCount = 1,
                    coupledStreamCount = channels - 1,
                    channelMapping = ShortArray(channels) { it.toShort() }
                )
            }

            if (data.size < 21 + channels) return null
            val streamCount = data[19].toInt() and 0xFF
            val coupledStreamCount = data[20].toInt() and 0xFF
            if (streamCount < 1 || coupledStreamCount > streamCount) return null
            if (streamCount > 255 - coupledStreamCount) return null

            val streamChannels = streamCount + coupledStreamCount
            val mapping = ShortArray(channels)
            for (i in 0 until channels) {
                val index = data[21 + i].toInt() and 0xFF
                if (index >= streamChannels && index != SILENT_CHANNEL) return null
                mapping[i] = index.toShort()
            }

            return OpusHead(
                channels = channels,
                preSkip = preSkip,
                inputSampleRate = inputSampleRate,
                outputGain = outputGain,
                mappingFamily = mappingFamily,
                streamCount = streamCount,
                coupledStreamCount = coupledStreamCount,
                channelMapping = mapping
            )
        }

        private fun readShortLe(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun readIntLe(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
