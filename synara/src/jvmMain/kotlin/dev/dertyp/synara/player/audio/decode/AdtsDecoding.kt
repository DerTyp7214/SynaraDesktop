package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.EXACT_SEEK_MAX_MS
import dev.dertyp.synara.player.audio.ProbeInfo
import dev.dertyp.synara.player.audio.readExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal object AdtsDecoding {
    fun bitRate(info: ProbeInfo.Adts): Long =
        (info.averageFrameBytes * 8 * info.header.sampleRate / AdtsHeader.SAMPLES_PER_FRAME).toLong()

    fun durationMs(info: ProbeInfo.Adts, size: Long): Long? {
        val bitRate = bitRate(info)
        if (size <= info.audioStart || bitRate <= 0) return null
        return (size - info.audioStart) * 8 * 1000 / bitRate
    }

    suspend fun open(source: AudioSource, info: ProbeInfo.Adts, startMs: Long, scope: CoroutineScope): DecodedStream? {
        val size = source.size()
        val durationMs = durationMs(info, size)
        val bitRate = bitRate(info)
        val exact = startMs <= EXACT_SEEK_MAX_MS
        val offset = if (exact || bitRate <= 0) {
            info.audioStart
        } else {
            val estimate = info.audioStart + startMs * bitRate / 8 / 1000
            if (size > 0) estimate.coerceAtMost(size - 1) else estimate
        }
        val resync = offset != info.audioStart
        val input = source.open(offset, scope) ?: return null
        val reference = info.header

        return launchDecoding(
            scope = scope,
            label = source.cacheKey,
            startMs = startMs,
            durationMs = durationMs,
            bitRate = bitRate.takeIf { it > 0 },
            trimFrames = { format -> if (exact) startMs * format.sampleRate / 1000 else 0L },
        ) { sink ->
            input.use { raw ->
                val stream = if (resync) {
                    FrameSync.find(raw) { bytes, i, length ->
                        AdtsHeader.parseConfirmed(bytes, i, length, reference) != null
                    } ?: return@use
                } else raw
                val decoder = AacFrameDecoder(reference.audioSpecificConfig())
                val headerBytes = ByteArray(9)
                while (currentCoroutineContext().isActive) {
                    if (readExactly(stream, headerBytes, 7) < 7) break
                    val header = AdtsHeader.parse(headerBytes, 0) ?: break
                    val remaining = header.frameLength - 7
                    val body = ByteArray(remaining)
                    if (readExactly(stream, body) < remaining) break
                    val payloadStart = header.headerLength - 7
                    if (payloadStart >= body.size) continue
                    decoder.decode(if (payloadStart == 0) body else body.copyOfRange(payloadStart, body.size), sink)
                }
            }
        }
    }
}
