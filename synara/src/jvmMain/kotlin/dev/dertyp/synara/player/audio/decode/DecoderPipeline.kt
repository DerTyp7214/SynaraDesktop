package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.PcmAccumulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class PcmFormat(val sampleRate: Int, val channels: Int)

class PcmSink internal constructor(private val channel: SendChannel<ShortBuffer>) {
    internal val format = CompletableDeferred<PcmFormat>()
    private var accumulator: PcmAccumulator? = null
    private var started: PcmFormat? = null

    fun accepts(sampleRate: Int, channels: Int): Boolean {
        val current = started
        if (current == null) {
            if (sampleRate <= 0 || channels <= 0) return false
            val newFormat = PcmFormat(sampleRate, channels)
            started = newFormat
            accumulator = PcmAccumulator(channels, channel)
            format.complete(newFormat)
            return true
        }
        return current.sampleRate == sampleRate && current.channels == channels
    }

    suspend fun write(data: ShortArray, length: Int) {
        accumulator?.append(data, length)
    }

    internal suspend fun finish() {
        accumulator?.flush()
    }
}

internal suspend fun launchDecoding(
    scope: CoroutineScope,
    label: String,
    startMs: Long,
    durationMs: Long?,
    bitRate: Long?,
    trimFrames: (PcmFormat) -> Long,
    timeout: Duration = 20.seconds,
    decode: suspend (PcmSink) -> Unit,
): DecodedStream {
    val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)
    val sink = PcmSink(pcmChannel)
    val job = scope.launch(Dispatchers.IO) {
        try {
            decode(sink)
            sink.finish()
        } catch (e: Exception) {
            if (e !is CancellationException && isActive) {
                println("Decoder error for $label: ${e.message}")
                if (sink.format.isActive) sink.format.completeExceptionally(e)
            }
        } finally {
            pcmChannel.close()
            if (sink.format.isActive) sink.format.completeExceptionally(EOFException("No audio decoded for $label"))
        }
    }

    val format = try {
        withTimeout(timeout) { sink.format.await() }
    } catch (e: TimeoutCancellationException) {
        pcmChannel.cancel()
        job.cancel()
        throw IOException("Timed out decoding $label")
    } catch (e: Throwable) {
        pcmChannel.cancel()
        job.cancel()
        throw e
    }

    val skipFrames = trimFrames(format).coerceAtLeast(0L)
    return DecodedStream(
        sampleRate = format.sampleRate,
        bitsPerSample = 16,
        channels = format.channels,
        startMs = startMs,
        durationMs = durationMs,
        bitRate = bitRate,
        pcmFlow = flow {
            var remainingSkip = skipFrames
            try {
                for (buffer in pcmChannel) {
                    if (remainingSkip > 0) {
                        val channels = format.channels.coerceAtLeast(1)
                        val frames = buffer.remaining() / channels
                        val drop = min(remainingSkip, frames.toLong()).toInt()
                        buffer.position(buffer.position() + drop * channels)
                        remainingSkip -= drop
                        if (!buffer.hasRemaining()) continue
                    }
                    emit(buffer)
                }
            } finally {
                pcmChannel.cancel()
            }
        }
    )
}
