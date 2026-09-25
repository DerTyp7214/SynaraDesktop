package dev.dertyp.synara.player.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.math.min

class FlowInputStream(
    private val flow: Flow<ByteArray>,
    scope: CoroutineScope
) : InputStream() {
    private val channel = Channel<ByteArray>(64)
    private var currentBuffer: ByteArray? = null
    private var bufferOffset = 0
    private var isClosed = false
    private val job: Job = scope.launch(Dispatchers.IO) {
        try {
            flow.collect {
                if (isClosed) throw CancellationException()
                channel.send(it)
            }
            channel.close()
        } catch (_: CancellationException) {
            channel.cancel()
        } catch (_: Exception) {
            channel.close()
        }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val r = read(b, 0, 1)
        return if (r == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isClosed) return -1
        if (len <= 0) return 0
        if (currentBuffer == null || bufferOffset >= currentBuffer!!.size) {
            currentBuffer = try {
                runBlocking { channel.receiveCatching().getOrNull() }
            } catch (_: Exception) {
                null
            }
            bufferOffset = 0
            if (currentBuffer == null) return -1
        }

        val remaining = currentBuffer!!.size - bufferOffset
        val toRead = min(len, remaining)
        System.arraycopy(currentBuffer!!, bufferOffset, b, off, toRead)
        bufferOffset += toRead
        return toRead
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            job.cancel()
            channel.close()
        }
        super.close()
    }
}
