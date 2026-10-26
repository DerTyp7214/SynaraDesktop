package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.ProbeInfo
import dev.dertyp.synara.player.audio.readExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

internal object AdtsDecoding {
    fun bitRate(info: ProbeInfo.Adts): Long =
        (info.averageFrameBytes * 8 * info.header.sampleRate / AdtsHeader.SAMPLES_PER_FRAME).toLong()

    fun durationMs(info: ProbeInfo.Adts, size: Long): Long? {
        val bitRate = bitRate(info)
        if (size <= info.audioStart || bitRate <= 0) return null
        return (size - info.audioStart) * 8 * 1000 / bitRate
    }

    suspend fun open(
        logger: Logger,
        source: AudioSource,
        info: ProbeInfo.Adts,
        startMs: Long,
        scope: CoroutineScope,
        scanBudget: Long = FrameSeek.SCAN_BUDGET,
    ): DecodedStream? {
        val size = source.size()
        val durationMs = durationMs(info, size)
        val bitRate = bitRate(info)
        val reference = info.header
        val upper = if (size > 0) (size - 1).coerceAtLeast(info.audioStart) else Long.MAX_VALUE
        val start = FrameSeek.start(
            source = source,
            layout = layout(reference),
            audioStart = info.audioStart,
            size = size,
            startMs = startMs,
            scope = scope,
            scanBudget = scanBudget,
            locate = { frame -> (info.audioStart + (frame * info.averageFrameBytes).toLong()).coerceIn(info.audioStart, upper) },
            landedFrame = { offset, _ -> ((offset - info.audioStart) / info.averageFrameBytes).roundToLong() },
        ) ?: return null

        return launchDecoding(
            logger = logger,
            scope = scope,
            label = source.cacheKey,
            startMs = start.startMs,
            durationMs = durationMs,
            bitRate = bitRate.takeIf { it > 0 },
            trimFrames = start::trimFrames,
        ) { sink ->
            start.input.use { stream ->
                val decoder = AacFrameDecoder(reference.audioSpecificConfig())
                val headerBytes = ByteArray(9)
                var discard = start.discardFrames
                while (currentCoroutineContext().isActive) {
                    if (readExactly(stream, headerBytes, 7) < 7) break
                    val header = AdtsHeader.parse(headerBytes, 0) ?: break
                    val remaining = header.frameLength - 7
                    val body = ByteArray(remaining)
                    if (readExactly(stream, body) < remaining) break
                    val emit = discard <= 0
                    if (!emit) discard--
                    val payloadStart = header.headerLength - 7
                    if (payloadStart >= body.size) continue
                    decoder.decode(if (payloadStart == 0) body else body.copyOfRange(payloadStart, body.size), sink, emit)
                }
            }
        }
    }

    private fun layout(reference: AdtsHeader) = object : FrameLayout {
        override val headerLength = 7
        override val samplesPerFrame = AdtsHeader.SAMPLES_PER_FRAME
        override val sampleRate = reference.sampleRate

        override fun frameLength(bytes: ByteArray, offset: Int): Int? =
            AdtsHeader.parse(bytes, offset)?.takeIf { it.isCompatible(reference) }?.frameLength

        override fun confirm(bytes: ByteArray, offset: Int, length: Int): Boolean =
            AdtsHeader.parseConfirmed(bytes, offset, length, reference) != null
    }
}
