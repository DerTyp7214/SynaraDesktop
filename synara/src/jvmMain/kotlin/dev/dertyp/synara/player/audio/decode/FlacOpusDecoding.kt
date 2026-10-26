package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.EXACT_SEEK_MAX_MS
import dev.dertyp.synara.player.audio.OpusHead
import dev.dertyp.synara.player.audio.PcmAccumulator
import dev.dertyp.synara.player.audio.convertToShortBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.gagravarr.ogg.OggFile
import org.gagravarr.ogg.OggPacket
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusPacketFactory
import org.gagravarr.opus.OpusTags
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal object FlacOpusDecoding {
    private class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
        var count = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value != -1) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) count += read
            return read
        }
    }

    private data class PlaybackInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val durationMs: Long? = null
    )

    suspend fun open(
        logger: Logger,
        source: AudioSource,
        metadata: ByteArray,
        isOgg: Boolean,
        positionMs: Long,
        totalDuration: Long,
        scope: CoroutineScope,
        durationMs: Long? = null
    ): DecodedStream? {
        val startMs = positionMs.coerceAtLeast(0L)
        val flacInfo = if (isOgg) null else FlacStreamInfo.parse(metadata)
        val oggStart = if (isOgg) OggTail.streamStart(metadata) else null
        val targetFrame = startMs * (flacInfo?.sampleRate ?: 48000) / 1000
        val landing = if (startMs > EXACT_SEEK_MAX_MS) {
            when {
                flacInfo != null -> FlacSeek.land(source, metadata, flacInfo, targetFrame, totalDuration, scope)
                oggStart != null -> OggOpusSeek.land(source, oggStart.sid, oggStart.preSkip, targetFrame, totalDuration, scope)
                else -> null
            }
        } else null

        val stream = when {
            landing == null -> source.open(0, scope) ?: return null
            isOgg -> landing.stream
            else -> SequenceInputStream(metadata.inputStream(), landing.stream)
        }

        val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)
        val infoDeferred = CompletableDeferred<PlaybackInfo>()

        val decoderJob = scope.launch(AppDispatchers.io) {
            val decoderJob = coroutineContext.job
            var failure: Exception? = null
            try {
                if (!isOgg) {
                    val decoder = FLACDecoder(stream)
                    decoder.addPCMProcessor(object : PCMProcessor {
                        override fun processStreamInfo(info: StreamInfo) {
                            infoDeferred.complete(
                                PlaybackInfo(
                                    info.sampleRate,
                                    info.channels,
                                    info.bitsPerSample,
                                    if (info.totalSamples > 0 && info.sampleRate > 0) info.totalSamples * 1000 / info.sampleRate else null
                                )
                            )
                        }

                        override fun processPCM(pcm: ByteData) {
                            val info = runBlocking(decoderJob) { infoDeferred.await() }
                            val shortBuffer =
                                convertToShortBuffer(pcm.data, pcm.len, info.bitsPerSample)
                            runBlocking(decoderJob) { pcmChannel.send(shortBuffer) }
                        }
                    })
                    decoder.decode()
                } else {
                    val counting = CountingInputStream(stream)
                    val ogg = OggFile(counting)
                    val reader = ogg.getPacketReader()
                    var opus: OpusStream? = null
                    var accumulator: PcmAccumulator? = null

                    if (landing != null) {
                        val metaOgg = OggFile(metadata.inputStream())
                        val metaReader = metaOgg.getPacketReader()
                        var metaPacket = metaReader.getNextPacket()
                        while (metaPacket != null) {
                            val opusPacket = OpusPacketFactory.create(metaPacket)
                            if (opusPacket is OpusInfo) {
                                val head = OpusHead.parse(opusPacket.data)
                                if (head != null) {
                                    opus = OpusStream(head, applyPreSkip = false)
                                    accumulator = PcmAccumulator(head.channels, pcmChannel)
                                    infoDeferred.complete(PlaybackInfo(48000, head.channels, 16, durationMs))
                                }
                                break
                            }
                            metaPacket = metaReader.getNextPacket()
                        }
                    }

                    val page = ArrayList<OggPacket>()
                    var trimsEnd = true
                    var preSkip = oggStart?.preSkip ?: 0
                    var streamSid = oggStart?.sid ?: 0
                    var frames = landing?.position ?: 0L
                    var streamFailure: IOException? = null
                    var pageEnd = 0L
                    var endOfStream = false

                    fun nextPacket(): OggPacket? {
                        val packet = try {
                            reader.getNextPacket()
                        } catch (e: IOException) {
                            streamFailure = e
                            null
                        }
                        if (packet != null) {
                            pageEnd = counting.count
                            endOfStream = packet.isEndOfStream
                        } else if (streamFailure == null && !endOfStream && counting.count > pageEnd) {
                            streamFailure = IOException("Stream of ${source.cacheKey} ended inside a page after ${counting.count} bytes")
                        }
                        return packet
                    }

                    suspend fun decodePage() {
                        val last = page.last()
                        val endsStream = trimsEnd && streamFailure == null && opus != null && last.isEndOfStream && last.sid == streamSid
                        val totalFrames = if (endsStream) OpusEndTrim.totalFrames(last.granulePosition, preSkip) else null
                        if (endsStream) {
                            trimsEnd = false
                            if (totalFrames == null || totalFrames < frames) {
                                logger.warning(
                                    DECODER_TAG,
                                    "End granule ${last.granulePosition} of ${source.cacheKey} does not fit pre-skip $preSkip and $frames frames before the last page"
                                )
                            }
                        }
                        for (queued in page) {
                            val currentStream = opus
                            val currentAccumulator = accumulator
                            val data = queued.data
                            if (currentStream != null && currentAccumulator != null && data.isNotEmpty()) {
                                val decodedShorts = currentStream.decode(data)
                                if (decodedShorts > 0) {
                                    val keptFrames = OpusEndTrim.framesToKeep(totalFrames, frames, decodedShorts / currentStream.channels)
                                    frames += keptFrames
                                    currentAccumulator.append(currentStream.output, keptFrames * currentStream.channels)
                                }
                            }
                        }
                        page.clear()
                    }

                    var packet = nextPacket()
                    while (packet != null || page.isNotEmpty()) {
                        val first = page.firstOrNull()
                        if (first != null && (packet == null || packet.sid != first.sid || packet.sequenceNumber != first.sequenceNumber)) {
                            decodePage()
                        }
                        if (packet != null) {
                            when (val opusPacket = OpusPacketFactory.create(packet)) {
                                is OpusInfo -> {
                                    if (opus == null) {
                                        val head = OpusHead.parse(opusPacket.data)
                                        if (head != null) {
                                            opus = OpusStream(head, applyPreSkip = landing == null)
                                            accumulator = PcmAccumulator(head.channels, pcmChannel)
                                            preSkip = head.preSkip
                                            streamSid = packet.sid
                                            infoDeferred.complete(PlaybackInfo(48000, head.channels, 16, durationMs))
                                        }
                                    }
                                }
                                is OpusTags -> {}
                                else -> page.add(packet)
                            }
                            packet = nextPacket()
                        }
                    }

                    accumulator?.flush()
                    streamFailure?.let { throw it }
                }
            } catch (e: Exception) {
                if (e !is CancellationException && isActive) {
                    failure = e
                    logger.error(DECODER_TAG, "Decoder error for ${source.cacheKey}: ${e.message}", e)
                    if (infoDeferred.isActive) infoDeferred.completeExceptionally(e)
                }
            } finally {
                pcmChannel.close(failure)
                stream.close()
            }
        }

        return try {
            val info = withTimeout(5.seconds) { infoDeferred.await() }
            val skipFrames = if (landing != null) targetFrame - landing.position else startMs * info.sampleRate / 1000
            DecodedStream(
                sampleRate = info.sampleRate,
                bitsPerSample = info.bitsPerSample,
                channels = info.channels,
                startMs = startMs,
                durationMs = info.durationMs,
                bitRate = null,
                pcmFlow = flow {
                    var remainingSkip = skipFrames
                    try {
                        for (buffer in pcmChannel) {
                            if (remainingSkip > 0) {
                                val channels = info.channels.coerceAtLeast(1)
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
        } catch (e: Exception) {
            pcmChannel.cancel()
            decoderJob.cancel()
            if (e is CancellationException) throw e
            null
        }
    }
}
