package dev.dertyp.synara.player.audio

import org.lwjgl.BufferUtils
import java.io.InputStream
import java.nio.ShortBuffer

const val EXACT_SEEK_MAX_MS = 5_000L

fun readExactly(input: InputStream, buffer: ByteArray, length: Int = buffer.size, offset: Int = 0): Int {
    var totalRead = 0
    while (totalRead < length) {
        val read = input.read(buffer, offset + totalRead, length - totalRead)
        if (read == -1) break
        totalRead += read
    }
    return totalRead
}

fun skipExactly(input: InputStream, count: Long): Boolean {
    var remaining = count
    val scratch = ByteArray(minOf(count, 64L * 1024).toInt().coerceAtLeast(1))
    while (remaining > 0) {
        val read = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (read == -1) return false
        remaining -= read
    }
    return true
}

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.u24(offset: Int): Int = (u8(offset) shl 16) or (u8(offset + 1) shl 8) or u8(offset + 2)

internal fun ByteArray.u32(offset: Int): Long =
    (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

internal fun ByteArray.u64(offset: Int): Long = (u32(offset) shl 32) or u32(offset + 4)

internal fun ByteArray.fourCc(offset: Int): String =
    String(CharArray(4) { (this[offset + it].toInt() and 0xFF).toChar() })

internal fun ByteArray.matches(offset: Int, text: String): Boolean {
    if (offset < 0 || offset + text.length > size) return false
    for (i in text.indices) if (this[offset + i] != text[i].code.toByte()) return false
    return true
}

fun convertToShortBuffer(
    data: ByteArray,
    len: Int,
    bitsPerSample: Int,
    isFloat: Boolean = false,
    unsigned8Bit: Boolean = false,
    bigEndian: Boolean = false
): ShortBuffer {
    val bytesPerSample = bitsPerSample / 8
    val samplesCount = len / bytesPerSample
    val shortBuffer = BufferUtils.createShortBuffer(samplesCount)

    when (bitsPerSample) {
        16 -> {
            if (bigEndian) {
                for (i in 0 until samplesCount) {
                    val msb = data[i * 2].toInt()
                    val lsb = data[i * 2 + 1].toInt() and 0xFF
                    shortBuffer.put(((msb shl 8) or lsb).toShort())
                }
            } else {
                for (i in 0 until samplesCount) {
                    val lsb = data[i * 2].toInt() and 0xFF
                    val msb = data[i * 2 + 1].toInt()
                    shortBuffer.put(((msb shl 8) or lsb).toShort())
                }
            }
        }

        24 -> {
            for (i in 0 until samplesCount) {
                val mid = data[i * 3 + 1].toInt() and 0xFF
                val msb = data[i * 3 + 2].toInt()
                shortBuffer.put(((msb shl 8) or mid).toShort())
            }
        }

        32 -> {
            if (isFloat) {
                for (i in 0 until samplesCount) {
                    val bits = (data[i * 4].toInt() and 0xFF) or
                            ((data[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            ((data[i * 4 + 2].toInt() and 0xFF) shl 16) or
                            ((data[i * 4 + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits).coerceIn(-1f, 1f)
                    shortBuffer.put((sample * Short.MAX_VALUE).toInt().toShort())
                }
            } else {
                for (i in 0 until samplesCount) {
                    val mid = data[i * 4 + 2].toInt() and 0xFF
                    val msb = data[i * 4 + 3].toInt()
                    shortBuffer.put(((msb shl 8) or mid).toShort())
                }
            }
        }

        8 -> {
            if (unsigned8Bit) {
                for (i in 0 until samplesCount) {
                    val s = (data[i].toInt() and 0xFF) - 128
                    shortBuffer.put((s shl 8).toShort())
                }
            } else {
                for (i in 0 until samplesCount) {
                    val s = data[i].toInt()
                    shortBuffer.put((s shl 8).toShort())
                }
            }
        }
    }

    return shortBuffer.flip()
}
