package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.ProbeInfo
import dev.dertyp.synara.player.audio.readExactly
import dev.dertyp.synara.player.audio.skipExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.InputStream

internal object Mp4AacDecoding {
    private const val MAX_INLINE_GAP = 1024L * 1024
    private const val PRE_ROLL_SAMPLES = 2

    suspend fun open(source: AudioSource, info: ProbeInfo.Mp4, startMs: Long, scope: CoroutineScope): DecodedStream? {
        val track = info.track
        if (track.sampleCount == 0) return null
        val target = startMs * track.timescale / 1000
        val index = if (startMs > 0) track.sampleAt(target) else 0
        val first = (index - PRE_ROLL_SAMPLES).coerceAtLeast(0)
        val trimUnits = if (startMs > 0) (target - track.times[first]).coerceAtLeast(0L) else 0L
        val initial = source.open(track.offsets[first], scope) ?: return null
        val durationMs = track.durationMs.takeIf { it > 0 }

        return launchDecoding(
            scope = scope,
            label = source.cacheKey,
            startMs = startMs,
            durationMs = durationMs,
            bitRate = track.bitRate.takeIf { it > 0 },
            trimFrames = { format ->
                if (track.timescale > 0) trimUnits * format.sampleRate / track.timescale else 0L
            },
        ) { sink ->
            val decoder = AacFrameDecoder(track.audioSpecificConfig)
            var stream: InputStream = initial
            var position = track.offsets[first]
            try {
                var frame = ByteArray(0)
                for (i in first until track.sampleCount) {
                    if (!currentCoroutineContext().isActive) break
                    val offset = track.offsets[i]
                    val size = track.sizes[i]
                    if (offset != position) {
                        val gap = offset - position
                        if (gap in 1..MAX_INLINE_GAP) {
                            if (!skipExactly(stream, gap)) break
                        } else {
                            stream.close()
                            stream = source.open(offset, scope) ?: break
                        }
                        position = offset
                    }
                    if (size <= 0) continue
                    if (frame.size != size) frame = ByteArray(size)
                    val read = readExactly(stream, frame)
                    position += read
                    if (read < size) break
                    decoder.decode(frame, sink)
                }
            } finally {
                stream.close()
            }
        }
    }
}
