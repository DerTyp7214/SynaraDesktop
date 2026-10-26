package dev.dertyp.synara.player.audio

import java.io.ByteArrayOutputStream

object FlacFixtures {
    private const val SAMPLE_RATE_CODE_44100 = 9
    private const val SAMPLE_SIZE_CODE_16 = 4
    private const val BLOCK_SIZE_CODE_16_BIT_FIELD = 7
    private const val VERBATIM_SUBFRAME_HEADER = 0x02
    private const val CONSTANT_SUBFRAME_HEADER = 0x00
    private const val SEEKTABLE = 3

    fun streamInfoMetadata(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        blockSize: Int
    ): ByteArray {
        val streamInfo = ByteArray(34)
        streamInfo[0] = (blockSize shr 8).toByte()
        streamInfo[1] = blockSize.toByte()
        streamInfo[2] = (blockSize shr 8).toByte()
        streamInfo[3] = blockSize.toByte()
        streamInfo[10] = (sampleRate shr 12).toByte()
        streamInfo[11] = (sampleRate shr 4).toByte()
        streamInfo[12] = (((sampleRate and 0x0F) shl 4) or ((channels - 1) shl 1) or ((bitsPerSample - 1) shr 4)).toByte()
        streamInfo[13] = ((((bitsPerSample - 1) and 0x0F) shl 4) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
        streamInfo[14] = (totalSamples shr 24).toByte()
        streamInfo[15] = (totalSamples shr 16).toByte()
        streamInfo[16] = (totalSamples shr 8).toByte()
        streamInfo[17] = totalSamples.toByte()
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo
    }

    fun verbatimStream44100Bit16(channelSamples: List<ShortArray>, blockSize: Int): ByteArray {
        val channels = channelSamples.size
        val total = channelSamples.first().size
        val out = ByteArrayOutputStream()
        out.write(streamInfoMetadata(44100, channels, 16, total.toLong(), blockSize))
        var frameNumber = 0
        var start = 0
        while (start < total) {
            val count = minOf(blockSize, total - start)
            out.write(verbatimFrame(channelSamples, start, count, frameNumber))
            frameNumber++
            start += count
        }
        return out.toByteArray()
    }

    fun stream44100Bit16(
        channelSamples: List<ShortArray>,
        blockSize: Int,
        seekPointEvery: Int = 0,
        variable: Boolean = false
    ): ByteArray {
        val channels = channelSamples.size
        val total = channelSamples.first().size
        val frames = ByteArrayOutputStream()
        val seekPoints = mutableListOf<Pair<Long, Long>>()
        var frameNumber = 0
        var start = 0
        while (start < total) {
            val count = minOf(blockSize, total - start)
            if (seekPointEvery > 0 && frameNumber % seekPointEvery == 0) seekPoints += start.toLong() to frames.size().toLong()
            frames.write(frame(channelSamples, start, count, if (variable) start else frameNumber, variable, constantWhenFlat = true))
            frameNumber++
            start += count
        }
        val out = ByteArrayOutputStream()
        val streamInfo = streamInfoMetadata(44100, channels, 16, total.toLong(), blockSize)
        if (seekPoints.isEmpty()) {
            out.write(streamInfo)
        } else {
            streamInfo[4] = 0
            out.write(streamInfo)
            val length = seekPoints.size * 18
            out.write(byteArrayOf((0x80 or SEEKTABLE).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()))
            for ((sample, offset) in seekPoints) {
                out.write(long64(sample))
                out.write(long64(offset))
                out.write(byteArrayOf((blockSize shr 8).toByte(), blockSize.toByte()))
            }
        }
        out.write(frames.toByteArray())
        return out.toByteArray()
    }

    fun frameHeader(channels: Int, count: Int, number: Int, variable: Boolean = false): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(0xFF)
        header.write(if (variable) 0xF9 else 0xF8)
        header.write((BLOCK_SIZE_CODE_16_BIT_FIELD shl 4) or SAMPLE_RATE_CODE_44100)
        header.write(((channels - 1) shl 4) or (SAMPLE_SIZE_CODE_16 shl 1))
        header.write(utf8Number(number))
        header.write((count - 1) shr 8)
        header.write((count - 1) and 0xFF)
        val headerBytes = header.toByteArray()
        return headerBytes + crc8(headerBytes).toByte()
    }

    private fun verbatimFrame(channelSamples: List<ShortArray>, start: Int, count: Int, frameNumber: Int): ByteArray =
        frame(channelSamples, start, count, frameNumber, variable = false, constantWhenFlat = false)

    private fun frame(
        channelSamples: List<ShortArray>,
        start: Int,
        count: Int,
        number: Int,
        variable: Boolean,
        constantWhenFlat: Boolean
    ): ByteArray {
        val frame = ByteArrayOutputStream()
        frame.write(frameHeader(channelSamples.size, count, number, variable))
        for (samples in channelSamples) {
            val flat = constantWhenFlat && (start until start + count).all { samples[it] == samples[start] }
            if (flat) {
                frame.write(CONSTANT_SUBFRAME_HEADER)
                frame.write(samples[start].toInt() shr 8)
                frame.write(samples[start].toInt() and 0xFF)
                continue
            }
            frame.write(VERBATIM_SUBFRAME_HEADER)
            for (i in start until start + count) {
                frame.write(samples[i].toInt() shr 8)
                frame.write(samples[i].toInt() and 0xFF)
            }
        }
        val body = frame.toByteArray()
        val crc = crc16(body)
        return body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun long64(value: Long): ByteArray = ByteArray(8) { (value shr (56 - it * 8)).toByte() }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    private fun utf8Number(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf(value.toByte())
        value < 0x800 -> byteArrayOf((0xC0 or (value shr 6)).toByte(), (0x80 or (value and 0x3F)).toByte())
        value < 0x10000 -> byteArrayOf(
            (0xE0 or (value shr 12)).toByte(),
            (0x80 or ((value shr 6) and 0x3F)).toByte(),
            (0x80 or (value and 0x3F)).toByte()
        )
        else -> byteArrayOf(
            (0xF0 or (value shr 18)).toByte(),
            (0x80 or ((value shr 12) and 0x3F)).toByte(),
            (0x80 or ((value shr 6) and 0x3F)).toByte(),
            (0x80 or (value and 0x3F)).toByte()
        )
    }
}
