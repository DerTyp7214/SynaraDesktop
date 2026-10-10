package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioProbe
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.ProbeInfo
import kotlinx.coroutines.CoroutineScope

class AudioDecoders(private val logger: Logger) {
    private val probe = AudioProbe(logger)

    suspend fun open(source: AudioSource, startMs: Long, scope: CoroutineScope): DecodedStream? {
        if (!source.prepare()) return null
        val info = probe.probe(source) ?: return null
        val start = startMs.coerceAtLeast(0L)
        return when (info) {
            is ProbeInfo.Pcm -> PcmDecoding.open(logger, source, info.header, start, scope)
            is ProbeInfo.Flac -> FlacOpusDecoding.open(
                logger, source, info.metadata, isOgg = false, start,
                totalDuration = source.durationMsHint?.takeIf { it > 0 } ?: info.durationMs ?: 0L,
                scope = scope
            )
            is ProbeInfo.OggOpus -> FlacOpusDecoding.open(
                logger, source, info.metadata, isOgg = true, start,
                totalDuration = source.durationMsHint ?: 0L,
                scope = scope
            )
            is ProbeInfo.Mp3 -> Mp3Decoding.open(logger, source, info, start, scope)
            is ProbeInfo.Mp4 -> Mp4AacDecoding.open(logger, source, info, start, scope)
            is ProbeInfo.Adts -> AdtsDecoding.open(logger, source, info, start, scope)
        }
    }
}
