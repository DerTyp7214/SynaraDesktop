package dev.dertyp.synara.player.audio

import kotlinx.coroutines.channels.SendChannel
import org.lwjgl.BufferUtils
import java.nio.ShortBuffer

class PcmAccumulator(
    channels: Int,
    private val target: SendChannel<ShortBuffer>,
    framesPerChunk: Int = FRAMES_PER_CHUNK
) {
    private val buffer = ShortArray(framesPerChunk * channels)
    private var count = 0

    suspend fun append(data: ShortArray, length: Int) {
        if (length <= 0) return
        if (length > buffer.size) {
            flush()
            send(data, length)
            return
        }
        if (count + length > buffer.size) flush()
        System.arraycopy(data, 0, buffer, count, length)
        count += length
    }

    suspend fun flush() {
        if (count <= 0) return
        send(buffer, count)
        count = 0
    }

    private suspend fun send(data: ShortArray, length: Int) {
        val shortBuffer = BufferUtils.createShortBuffer(length)
        shortBuffer.put(data, 0, length)
        target.send(shortBuffer.flip())
    }

    companion object {
        const val FRAMES_PER_CHUNK = 48000 / 5
    }
}
