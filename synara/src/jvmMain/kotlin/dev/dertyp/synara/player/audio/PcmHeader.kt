package dev.dertyp.synara.player.audio

import java.io.InputStream

data class PcmHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val aiffCompression: AiffHeader.Compression?,
    val dataStart: Long,
    val dataSize: Long,
) {
    val bytesPerSample: Int get() = (bitsPerSample + 7) / 8
    val bytesPerFrame: Int get() = bytesPerSample * channels

    fun normalize(buffer: ByteArray, length: Int) {
        aiffCompression?.let { AiffHeader.toLittleEndian(buffer, length, bytesPerSample, it) }
    }

    fun encode(): ByteArray {
        val out = ByteArray(ENCODED_SIZE)
        MAGIC.copyInto(out)
        out[4] = 1 // version
        writeInt(out, 5, sampleRate)
        out[9] = channels.toByte()
        out[10] = bitsPerSample.toByte()
        out[11] = if (isFloat) 1 else 0
        out[12] = aiffCompression?.let { (it.ordinal + 1).toByte() } ?: 0
        writeLong(out, 13, dataStart)
        writeLong(out, 21, dataSize)
        return out
    }

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte())
        private const val ENCODED_SIZE = 29

        private const val MAX_HEADER_BYTES = 16L * 1024 * 1024

        private const val RIFF = 0x52494646 // "RIFF"
        private const val WAVE = 0x57415645 // "WAVE"
        private const val FMT = 0x666D7420 // "fmt "
        private const val DATA = 0x64617461 // "data"

        private const val WAVE_FORMAT_PCM = 1
        private const val WAVE_FORMAT_IEEE_FLOAT = 3
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        fun decode(bytes: ByteArray): PcmHeader? {
            if (bytes.size != ENCODED_SIZE) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
            if (bytes[4].toInt() != 1) return null
            val compressionIndex = bytes[12].toInt()
            return PcmHeader(
                sampleRate = AiffHeader.readInt(bytes, 5),
                channels = bytes[9].toInt(),
                bitsPerSample = bytes[10].toInt(),
                isFloat = bytes[11].toInt() == 1,
                aiffCompression = if (compressionIndex == 0) null
                else AiffHeader.Compression.entries.getOrNull(compressionIndex - 1),
                dataStart = readLong(bytes, 13),
                dataSize = readLong(bytes, 21),
            )
        }

        fun isWav(header: ByteArray, offset: Int = 0): Boolean {
            if (header.size - offset < 12) return false
            return AiffHeader.readInt(header, offset) == RIFF && AiffHeader.readInt(header, offset + 8) == WAVE
        }

        fun parseFromStream(input: InputStream, streamOffsetOfMagic: Long): PcmHeader? {
            val head = ByteArray(12)
            if (!readFully(input, head, 12)) return null
            return when {
                isWav(head) -> parseWav(input, streamOffsetOfMagic)
                AiffHeader.isAiff(head) -> parseAiff(
                    input,
                    streamOffsetOfMagic,
                    isAifc = AiffHeader.readInt(head, 8) == AiffHeader.AIFC
                )

                else -> null
            }
        }

        private fun parseWav(input: InputStream, base: Long): PcmHeader? {
            var position = 12L
            var format: Int? = null
            var channels = 0
            var sampleRate = 0
            var bits = 0

            val chunkHeader = ByteArray(8)
            while (position < MAX_HEADER_BYTES) {
                if (!readFully(input, chunkHeader, 8)) return null
                position += 8
                val id = AiffHeader.readInt(chunkHeader, 0)
                val size = readIntLe(chunkHeader, 4).toLong() and 0xFFFFFFFFL

                when (id) {
                    FMT -> {
                        val toRead = minOf(size, 64L).toInt()
                        val payload = ByteArray(toRead)
                        if (!readFully(input, payload, toRead)) return null
                        position += toRead
                        if (toRead < 16) return null

                        var audioFormat = readShortLe(payload, 0)
                        channels = readShortLe(payload, 2)
                        sampleRate = readIntLe(payload, 4)
                        bits = readShortLe(payload, 14)
                        if (audioFormat == WAVE_FORMAT_EXTENSIBLE) {
                            // cbSize(2) validBits(2) channelMask(4) subFormat GUID: first 2 bytes = format
                            if (toRead < 26) return null
                            audioFormat = readShortLe(payload, 24)
                        }
                        format = audioFormat
                        if (!skipFully(input, size - toRead + (size and 1L))) return null
                        position += size - toRead + (size and 1L)
                    }

                    DATA -> {
                        val audioFormat = format ?: return null
                        val isFloat = audioFormat == WAVE_FORMAT_IEEE_FLOAT
                        val supported = channels in 1..8 && sampleRate > 0 && when {
                            isFloat -> bits == 32
                            audioFormat == WAVE_FORMAT_PCM -> bits == 8 || bits == 16 || bits == 24 || bits == 32
                            else -> false
                        }
                        if (!supported) return null
                        val dataSize = if (size == 0L || size == 0xFFFFFFFFL) 0L else size
                        return PcmHeader(
                            sampleRate = sampleRate,
                            channels = channels,
                            bitsPerSample = bits,
                            isFloat = isFloat,
                            aiffCompression = null,
                            dataStart = base + position,
                            dataSize = dataSize,
                        )
                    }

                    else -> {
                        val skip = size + (size and 1L)
                        if (!skipFully(input, skip)) return null
                        position += skip
                    }
                }
            }
            return null
        }

        private fun parseAiff(input: InputStream, base: Long, isAifc: Boolean): PcmHeader? {
            var position = 12L
            val chunkHeader = ByteArray(AiffHeader.CHUNK_HEADER_SIZE)
            while (position < MAX_HEADER_BYTES) {
                if (!readFully(input, chunkHeader, AiffHeader.CHUNK_HEADER_SIZE)) return null
                position += AiffHeader.CHUNK_HEADER_SIZE
                val id = AiffHeader.readInt(chunkHeader, 0)
                val size = AiffHeader.readUnsignedInt(chunkHeader, 4)

                when (id) {
                    AiffHeader.COMM -> {
                        val toRead = minOf(size, 64L).toInt()
                        val payload = ByteArray(toRead)
                        if (!readFully(input, payload, toRead)) return null
                        position += toRead
                        val comm = AiffHeader.parseComm(payload, toRead, isAifc) ?: return null
                        if (!comm.isSupported) return null
                        if (!skipFully(input, size - toRead + (size and 1L))) return null
                        position += size - toRead + (size and 1L)

                        return findSsnd(input, base, position, comm)
                    }

                    AiffHeader.SSND -> return null

                    else -> {
                        val skip = size + (size and 1L)
                        if (!skipFully(input, skip)) return null
                        position += skip
                    }
                }
            }
            return null
        }

        private fun findSsnd(input: InputStream, base: Long, startPosition: Long, comm: AiffHeader.Comm): PcmHeader? {
            var position = startPosition
            val chunkHeader = ByteArray(AiffHeader.CHUNK_HEADER_SIZE)
            while (position < MAX_HEADER_BYTES) {
                if (!readFully(input, chunkHeader, AiffHeader.CHUNK_HEADER_SIZE)) return null
                position += AiffHeader.CHUNK_HEADER_SIZE
                val id = AiffHeader.readInt(chunkHeader, 0)
                val size = AiffHeader.readUnsignedInt(chunkHeader, 4)

                if (id == AiffHeader.SSND) {
                    val ssndHeader = ByteArray(AiffHeader.SSND_HEADER_SIZE)
                    if (!readFully(input, ssndHeader, AiffHeader.SSND_HEADER_SIZE)) return null
                    position += AiffHeader.SSND_HEADER_SIZE
                    val offset = AiffHeader.readUnsignedInt(ssndHeader, 0)
                    if (!skipFully(input, offset)) return null
                    position += offset

                    val dataSize = if (size == 0L || size == 0xFFFFFFFFL) 0L
                    else (size - AiffHeader.SSND_HEADER_SIZE - offset).coerceAtLeast(0L)
                    return PcmHeader(
                        sampleRate = comm.sampleRate,
                        channels = comm.channelCount,
                        bitsPerSample = comm.sampleSizeBits,
                        isFloat = comm.compression.isFloat,
                        aiffCompression = comm.compression,
                        dataStart = base + position,
                        dataSize = dataSize,
                    )
                }

                val skip = size + (size and 1L)
                if (!skipFully(input, skip)) return null
                position += skip
            }
            return null
        }

        private fun readIntLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readShortLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readLong(bytes: ByteArray, offset: Int): Long =
            (AiffHeader.readInt(bytes, offset).toLong() shl 32) or
                    (AiffHeader.readInt(bytes, offset + 4).toLong() and 0xFFFFFFFFL)

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }

        private fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
            writeInt(bytes, offset, (value ushr 32).toInt())
            writeInt(bytes, offset + 4, value.toInt())
        }

        private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
            var total = 0
            while (total < length) {
                val read = input.read(buffer, total, length - total)
                if (read == -1) return false
                total += read
            }
            return true
        }

        private fun skipFully(input: InputStream, count: Long): Boolean {
            var remaining = count
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val read = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (read == -1) return false
                remaining -= read
            }
            return true
        }
    }
}
