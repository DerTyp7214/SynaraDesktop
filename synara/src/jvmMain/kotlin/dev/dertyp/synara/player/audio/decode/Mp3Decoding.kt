package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.ProbeInfo
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.BitstreamErrors
import javazoom.jl.decoder.BitstreamException
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.DecoderException
import javazoom.jl.decoder.JavaLayerException
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.math.roundToLong

internal object Mp3Decoding {
    fun durationMs(info: ProbeInfo.Mp3, size: Long): Long? {
        info.xing?.durationMs(info.header)?.let { return it }
        info.vbri?.durationMs(info.header)?.let { return it }
        if (size <= 0 || info.header.bitRateKbps <= 0) return null
        val audioBytes = size - info.audioStart
        if (audioBytes <= 0) return null
        return audioBytes * 8 / info.header.bitRateKbps
    }

    suspend fun open(
        logger: Logger,
        source: AudioSource,
        info: ProbeInfo.Mp3,
        startMs: Long,
        scope: CoroutineScope,
        scanBudget: Long = FrameSeek.SCAN_BUDGET,
    ): DecodedStream? {
        val size = source.size()
        val durationMs = durationMs(info, size)
        val header = info.header
        val seekDurationMs = durationMs ?: source.durationMsHint
        val start = FrameSeek.start(
            source = source,
            layout = layout(header),
            audioStart = info.audioStart,
            size = size,
            startMs = startMs,
            scope = scope,
            scanBudget = scanBudget,
            locate = { frame -> seekOffset(info, frame * header.samplesPerFrame * 1000 / header.sampleRate, size, seekDurationMs) },
            landedFrame = { offset, frame -> landedFrame(info, offset, frame, size, seekDurationMs) },
        ) ?: return null
        val bitRate = if (durationMs != null && durationMs > 0 && size > 0) {
            (size - info.audioStart) * 8 * 1000 / durationMs
        } else header.bitRateKbps * 1000L

        return launchDecoding(
            logger = logger,
            scope = scope,
            label = source.cacheKey,
            startMs = start.startMs,
            durationMs = durationMs,
            bitRate = bitRate,
            trimFrames = start::trimFrames,
        ) { sink ->
            start.input.use { stream ->
                val bitstream = Bitstream(stream)
                val decoder = Decoder()
                var discard = start.discardFrames
                try {
                    while (currentCoroutineContext().isActive) {
                        val frame = try {
                            bitstream.readFrame()
                        } catch (e: BitstreamException) {
                            streamFailure(e)?.let { throw it }
                            null
                        } ?: break
                        val emit = discard <= 0
                        if (!emit) discard--
                        try {
                            val output = decoder.decodeFrame(frame, bitstream) as SampleBuffer
                            if (emit && sink.accepts(output.sampleFrequency, output.channelCount)) {
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

    private fun layout(header: Mp3Header) = object : FrameLayout {
        override val headerLength = 4
        override val samplesPerFrame = header.samplesPerFrame
        override val sampleRate = header.sampleRate

        override fun frameLength(bytes: ByteArray, offset: Int): Int? =
            Mp3Header.parse(bytes, offset)?.takeIf { it.isCompatible(header) }?.frameLength

        override fun confirm(bytes: ByteArray, offset: Int, length: Int): Boolean =
            Mp3Header.parseConfirmed(bytes, offset, length, header) != null
    }

    private fun usesSeekTable(info: ProbeInfo.Mp3, durationMs: Long?): Boolean =
        info.xing?.toc != null && durationMs != null && durationMs > 0 || info.vbri != null

    private fun landedFrame(info: ProbeInfo.Mp3, offset: Long, frame: Long, size: Long, durationMs: Long?): Long {
        if (usesSeekTable(info, durationMs)) return frame
        val header = info.header
        val bytes = (offset - info.audioStart).toDouble()
        val ms = if (size > info.audioStart && durationMs != null && durationMs > 0) {
            bytes * durationMs / (size - info.audioStart)
        } else {
            bytes * 8 / header.bitRateKbps
        }
        return (ms * header.sampleRate / 1000 / header.samplesPerFrame).roundToLong()
    }

    private fun streamFailure(error: BitstreamException): IOException? {
        if (error.errorCode != BitstreamErrors.STREAM_ERROR) return null
        var cause: Throwable? = error
        while (cause is JavaLayerException) cause = cause.exception
        return cause as? IOException
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
