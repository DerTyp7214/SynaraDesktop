package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.EXACT_SEEK_MAX_MS
import dev.dertyp.synara.player.audio.OpusHead
import dev.dertyp.synara.player.audio.PcmAccumulator
import dev.dertyp.synara.player.audio.convertToShortBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.gagravarr.ogg.OggFile
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusPacketFactory
import org.gagravarr.opus.OpusTags
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ShortBuffer
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal object FlacOpusDecoding {
    private data class PlaybackInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )

    suspend fun open(
        source: AudioSource,
        metadata: ByteArray,
        isOgg: Boolean,
        positionMs: Long,
        totalDuration: Long,
        scope: CoroutineScope
    ): DecodedStream? {
        val startMs = positionMs.coerceAtLeast(0L)
        val mSize = metadata.size.toLong()
        val exactSeek = startMs in 1..EXACT_SEEK_MAX_MS

        val byteOffset = if (startMs > 0 && totalDuration > 0 && !exactSeek) {
            val fileSize = source.size()
            val audioDataSize = fileSize - mSize
            if (audioDataSize > 0) {
                mSize + (startMs.toDouble() / totalDuration * audioDataSize).toLong()
            } else 0L
        } else 0L

        val rawStream = source.open(byteOffset, scope) ?: return null

        val stream = if (byteOffset > 0) {
            val syncStream = object : InputStream() {
                private var foundSync = false
                private val pushbackQueue = ArrayDeque<Int>()

                override fun read(): Int {
                    if (pushbackQueue.isNotEmpty()) return pushbackQueue.poll()
                    if (foundSync) return rawStream.read()

                    if (isOgg) {
                        var state = 0
                        while (true) {
                            val b = rawStream.read()
                            if (b == -1) return -1
                            when (state) {
                                0 -> state = if (b == 'O'.code) 1 else 0
                                1 -> state = if (b == 'g'.code) 2 else if (b == 'O'.code) 1 else 0
                                2 -> state = if (b == 'g'.code) 3 else if (b == 'O'.code) 1 else 0
                                3 -> when (b) {
                                    'S'.code -> {
                                        foundSync = true
                                        pushbackQueue.add('g'.code)
                                        pushbackQueue.add('g'.code)
                                        pushbackQueue.add('S'.code)
                                        return 'O'.code
                                    }
                                    'O'.code -> state = 1
                                    else -> state = 0
                                }
                            }
                        }
                    } else {
                        var b = rawStream.read()
                        while (b != -1) {
                            if (b == 0xFF) {
                                val b2 = rawStream.read()
                                if (b2 == -1) return -1
                                if ((b2 and 0xFC) == 0xF8) {
                                    foundSync = true
                                    pushbackQueue.add(b2)
                                    return 0xFF
                                }
                                b = b2
                            } else {
                                b = rawStream.read()
                            }
                        }
                    }
                    return -1
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len <= 0) return 0
                    if (foundSync && pushbackQueue.isEmpty()) return rawStream.read(b, off, len)
                    val next = read()
                    if (next == -1) return -1
                    b[off] = next.toByte()
                    return 1
                }

                override fun close() = rawStream.close()
            }
            if (isOgg) syncStream else SequenceInputStream(metadata.inputStream(), syncStream)
        } else {
            rawStream
        }

        val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)
        val infoDeferred = CompletableDeferred<PlaybackInfo>()

        val decoderJob = scope.launch(Dispatchers.IO) {
            val decoderJob = coroutineContext.job
            try {
                if (!isOgg) {
                    val decoder = FLACDecoder(stream)
                    decoder.addPCMProcessor(object : PCMProcessor {
                        override fun processStreamInfo(info: StreamInfo) {
                            infoDeferred.complete(
                                PlaybackInfo(
                                    info.sampleRate,
                                    info.channels,
                                    info.bitsPerSample
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
                    val ogg = OggFile(stream)
                    val reader = ogg.getPacketReader()
                    var opus: OpusStream? = null
                    var accumulator: PcmAccumulator? = null

                    if (byteOffset > 0) {
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
                                    infoDeferred.complete(PlaybackInfo(48000, head.channels, 16))
                                }
                                break
                            }
                            metaPacket = metaReader.getNextPacket()
                        }
                    }

                    var packet = reader.getNextPacket()
                    while (packet != null) {
                        when (val opusPacket = OpusPacketFactory.create(packet)) {
                            is OpusInfo -> {
                                if (opus == null) {
                                    val head = OpusHead.parse(opusPacket.data)
                                    if (head != null) {
                                        opus = OpusStream(head, applyPreSkip = byteOffset == 0L)
                                        accumulator = PcmAccumulator(head.channels, pcmChannel)
                                        infoDeferred.complete(PlaybackInfo(48000, head.channels, 16))
                                    }
                                }
                            }
                            is OpusTags -> {}
                            else -> {
                                val currentStream = opus
                                val currentAccumulator = accumulator
                                val data = packet.data
                                if (currentStream != null && currentAccumulator != null && data.isNotEmpty()) {
                                    val decodedShorts = currentStream.decode(data)
                                    if (decodedShorts > 0) {
                                        currentAccumulator.append(currentStream.output, decodedShorts)
                                    }
                                }
                            }
                        }
                        packet = reader.getNextPacket()
                    }

                    accumulator?.flush()
                }
            } catch (e: Exception) {
                if (e !is CancellationException && isActive) {
                    println("Decoder error for ${source.cacheKey}: ${e.message}")
                    if (infoDeferred.isActive) infoDeferred.completeExceptionally(e)
                }
            } finally {
                pcmChannel.close()
                stream.close()
            }
        }

        return try {
            val info = withTimeout(5.seconds) { infoDeferred.await() }
            val skipFrames = if (exactSeek) startMs * info.sampleRate / 1000 else 0L
            DecodedStream(
                sampleRate = info.sampleRate,
                bitsPerSample = info.bitsPerSample,
                channels = info.channels,
                startMs = startMs,
                durationMs = null,
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
