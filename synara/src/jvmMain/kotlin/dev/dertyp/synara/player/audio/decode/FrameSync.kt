package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.readExactly
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

internal object FrameSync {
    private const val CHUNK = 64 * 1024
    private const val MAX_SCAN = 1024 * 1024

    fun find(input: InputStream, confirm: (ByteArray, Int, Int) -> Boolean): InputStream? {
        var buffer = ByteArray(CHUNK)
        var length = readExactly(input, buffer)
        var from = 0
        while (true) {
            val eof = length < buffer.size
            for (i in from until length - 1) {
                if (buffer[i] == 0xFF.toByte() && confirm(buffer, i, length)) {
                    return SequenceInputStream(ByteArrayInputStream(buffer, i, length - i), input)
                }
            }
            if (eof || length >= MAX_SCAN) return null
            from = (length - 8 * 1024).coerceAtLeast(0)
            val grown = buffer.copyOf(buffer.size + CHUNK)
            val read = readExactly(input, grown, CHUNK, length)
            buffer = grown
            length += read
            if (read <= 0) return null
        }
    }
}
