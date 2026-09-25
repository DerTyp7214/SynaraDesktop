package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.EXACT_SEEK_MAX_MS
import dev.dertyp.synara.player.audio.ProbeInfo
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.BitstreamException
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.DecoderException
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal object Mp3Decoding {
    fun durationMs(info: ProbeInfo.Mp3, size: Long): Long? {
        info.xing?.durationMs(info.header)?.let { return it }
        info.vbri?.durationMs(info.header)?.let { return it }
        if (size <= 0 || info.header.bitRateKbps <= 0) return null
        val audioBytes = size - info.audioStart
        if (audioBytes <= 0) return null
        return audioBytes * 8 / info.header.bitRateKbps
    }

    suspend fun open(source: AudioSource, info: ProbeInfo.Mp3, startMs: Long, scope: CoroutineScope): DecodedStream? {
        val size = source.size()
        val durationMs = durationMs(info, size)
        val header = info.header
        val exact = startMs <= EXACT_SEEK_MAX_MS
        val offset = if (exact) info.audioStart else seekOffset(info, startMs, size, durationMs ?: source.durationMsHint)
        val resync = offset != info.audioStart
        val input = source.open(offset, scope) ?: return null
        val bitRate = if (durationMs != null && durationMs > 0 && size > 0) {
            (size - info.audioStart) * 8 * 1000 / durationMs
        } else header.bitRateKbps * 1000L

        return launchDecoding(
            scope = scope,
            label = source.cacheKey,
            startMs = startMs,
            durationMs = durationMs,
            bitRate = bitRate,
            trimFrames = { format -> if (exact) startMs * format.sampleRate / 1000 else 0L },
        ) { sink ->
            input.use { raw ->
                val stream = if (resync) {
                    FrameSync.find(raw) { bytes, i, length ->
                        Mp3Header.parseConfirmed(bytes, i, length, header) != null
                    } ?: return@use
                } else raw
                val bitstream = Bitstream(stream)
                val decoder = Decoder()
                var dropNext = resync
                try {
                    while (currentCoroutineContext().isActive) {
                        val frame = try {
                            bitstream.readFrame()
                        } catch (_: BitstreamException) {
                            null
                        } ?: break
                        try {
                            val output = decoder.decodeFrame(frame, bitstream) as SampleBuffer
                            if (dropNext) {
                                dropNext = false
                            } else if (sink.accepts(output.sampleFrequency, output.channelCount)) {
                                sink.write(output.buffer, output.bufferLength)
                            }
                        } catch (_: DecoderException) {
                        } catch (_: ArrayIndexOutOfBoundsException) {
                        } finally {
                            bitstream.closeFrame()
                        }
                    }
                } finally {
                    try {
                        bitstream.close()
                    } catch (_: BitstreamException) {
                    }
                }
            }
        }
    }

    private fun seekOffset(info: ProbeInfo.Mp3, startMs: Long, size: Long, durationMs: Long?): Long {
        val header = info.header
        val xing = info.xing
        val vbri = info.vbri
        val offset = when {
            xing?.toc != null && durationMs != null && durationMs > 0 -> {
                val dataSize = xing.bytes?.takeIf { it > 0 } ?: (size - info.firstFrameOffset)
                xing.seekOffset(startMs, durationMs, dataSize)?.let { info.firstFrameOffset + it }
            }
            vbri != null -> vbri.seekOffset(startMs, header)?.let { info.firstFrameOffset + header.frameLength + it }
            else -> null
        } ?: if (size > info.audioStart && durationMs != null && durationMs > 0) {
            info.audioStart + ((size - info.audioStart).toDouble() * startMs / durationMs).toLong()
        } else {
            info.audioStart + startMs * header.bitRateKbps / 8
        }
        val upper = if (size > 0) (size - 1).coerceAtLeast(info.audioStart) else Long.MAX_VALUE
        return offset.coerceIn(info.audioStart, upper)
    }
}
