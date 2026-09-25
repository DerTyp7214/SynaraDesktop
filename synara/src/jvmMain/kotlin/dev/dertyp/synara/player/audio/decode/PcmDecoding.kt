package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.PcmHeader
import dev.dertyp.synara.player.audio.convertToShortBuffer
import dev.dertyp.synara.player.audio.readExactly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ShortBuffer
import kotlin.math.min

internal object PcmDecoding {
    suspend fun open(
        source: AudioSource,
        header: PcmHeader,
        positionMs: Long,
        scope: CoroutineScope
    ): DecodedStream? {
        val bytesPerFrame = header.bytesPerFrame
        if (bytesPerFrame <= 0 || header.sampleRate <= 0) return null

        var byteOffset = header.dataStart
        if (positionMs > 0) {
            var frame = positionMs * header.sampleRate / 1000
            if (header.dataSize > 0) {
                val lastFrame = (header.dataSize / bytesPerFrame - 1).coerceAtLeast(0)
                frame = frame.coerceAtMost(lastFrame)
            }
            byteOffset = header.dataStart + frame * bytesPerFrame
        }

        val rawStream = source.open(byteOffset, scope) ?: return null

        val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)

        scope.launch(Dispatchers.IO) {
            try {
                val chunkBytes = ((32 * 1024) / bytesPerFrame).coerceAtLeast(1) * bytesPerFrame
                val buffer = ByteArray(chunkBytes)
                var remaining = if (header.dataSize > 0) {
                    header.dataSize - (byteOffset - header.dataStart)
                } else Long.MAX_VALUE

                while (remaining > 0) {
                    val want = min(chunkBytes.toLong(), remaining).toInt()
                    val read = readExactly(rawStream, buffer, want)
                    if (read <= 0) break

                    val wholeFrames = (read / bytesPerFrame) * bytesPerFrame
                    if (wholeFrames > 0) {
                        header.normalize(buffer, wholeFrames)
                        pcmChannel.send(
                            convertToShortBuffer(
                                buffer,
                                wholeFrames,
                                header.bitsPerSample,
                                isFloat = header.isFloat,
                                unsigned8Bit = true
                            )
                        )
                    }

                    remaining -= read
                    if (read < want) break
                }
            } catch (e: Exception) {
                if (e !is CancellationException && isActive) {
                    println("PCM decoder error for ${source.cacheKey}: ${e.message}")
                }
            } finally {
                pcmChannel.close()
                rawStream.close()
            }
        }

        return DecodedStream(
            sampleRate = header.sampleRate,
            bitsPerSample = header.bitsPerSample,
            channels = header.channels,
            startMs = positionMs,
            durationMs = null,
            bitRate = null,
            pcmFlow = flow {
                try {
                    for (buffer in pcmChannel) {
                        emit(buffer)
                    }
                } finally {
                    pcmChannel.cancel()
                }
            }
        )
    }
}
