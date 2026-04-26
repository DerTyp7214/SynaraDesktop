package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import io.github.jaredmdobson.concentus.OpusDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.gagravarr.ogg.OggFile
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusPacketFactory
import org.gagravarr.opus.OpusTags
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import org.lwjgl.BufferUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ShortBuffer
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class SongDataSource(
    private val songService: ISongService,
    private val songCache: SongCache,
    private val settings: Settings
) {
    @Suppress("PrivatePropertyName")
    private val MAX_METADATA_CACHE_SIZE = 50
    private val cacheMutex = Mutex()

    private val metadataCache =
        object : LinkedHashMap<String, ByteArray>(MAX_METADATA_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
                size > MAX_METADATA_CACHE_SIZE
        }

    suspend fun getSong(songId: PlatformUUID): UserSong? {
        return songCache.get(songId) ?: songService.byId(songId)?.also { song ->
            songCache.put(song)
        }
    }

    suspend fun getMetadata(songId: PlatformUUID): ByteArray? = withContext(Dispatchers.IO) {
        val quality = settings.get(SettingKey.StreamingQuality, 0)
        val cacheKey = "$songId:$quality"
        
        cacheMutex.withLock {
            metadataCache[cacheKey]?.let { return@withContext it }
        }

        try {
            val flow = if (quality == 0) {
                songService.streamSong(songId, 0)
            } else {
                songService.downloadSong(songId, quality, 0, force = false)
            } ?: return@withContext null
            val stream = FlowInputStream(flow, this)
            val bos = ByteArrayOutputStream()

            val initialBuffer = ByteArray(8192)
            val firstRead = readExactly(stream, initialBuffer)
            if (firstRead < 4) {
                stream.close()
                return@withContext null
            }

            var foundFlac = false
            var foundOgg = false
            var magicOffset = -1

            for (i in 0 until firstRead - 3) {
                if (initialBuffer[i] == 'f'.code.toByte() && initialBuffer[i + 1] == 'L'.code.toByte() &&
                    initialBuffer[i + 2] == 'a'.code.toByte() && initialBuffer[i + 3] == 'C'.code.toByte()
                ) {
                    foundFlac = true
                    magicOffset = i
                    break
                }
                if (initialBuffer[i] == 'O'.code.toByte() && initialBuffer[i + 1] == 'g'.code.toByte() &&
                    initialBuffer[i + 2] == 'g'.code.toByte() && initialBuffer[i + 3] == 'S'.code.toByte()
                ) {
                    foundOgg = true
                    magicOffset = i
                    break
                }
            }

            if (foundFlac) {
                val bis = SequenceInputStream(
                    initialBuffer.sliceArray(magicOffset until firstRead).inputStream(),
                    stream
                )

                val magic = ByteArray(4)
                readExactly(bis, magic)
                bos.write(magic)

                var lastBlock = false
                while (!lastBlock) {
                    val header = ByteArray(4)
                    if (readExactly(bis, header) != 4) break
                    bos.write(header)

                    lastBlock = (header[0].toInt() and 0x80) != 0
                    val length = ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)

                    if (length > 0) {
                        val data = ByteArray(length)
                        if (readExactly(bis, data) != length) break
                        bos.write(data)
                    }
                }
            } else if (foundOgg) {
                bos.write(initialBuffer, magicOffset, firstRead - magicOffset)
            } else {
                stream.close()
                return@withContext null
            }

            stream.close()
            val metadata = bos.toByteArray()
            if (metadata.isNotEmpty()) {
                cacheMutex.withLock {
                    metadataCache[cacheKey] = metadata
                }
                metadata
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readExactly(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    data class PlaybackSession(
        val song: UserSong,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val pcmFlow: Flow<ShortBuffer>
    )

    private data class PlaybackInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )

    suspend fun createPlaybackSession(
        songId: PlatformUUID,
        positionMs: Long,
        sessionScope: CoroutineScope
    ): PlaybackSession? {
        val song = getSong(songId) ?: return null
        val metadata = getMetadata(songId) ?: return null

        val totalDuration = song.duration
        val mSize = metadata.size.toLong()

        val quality = settings.get(SettingKey.StreamingQuality, 0)

        val byteOffset = if (positionMs > 0 && totalDuration > 0) {
            val fileSize = if (quality == 0) {
                if (song.fileSize > 0) song.fileSize else songService.getStreamSize(songId)
            } else {
                val size = songService.getDownloadSize(songId, quality, force = false)
                if (size > 0) size else song.fileSize
            }
            val audioDataSize = fileSize - mSize
            if (audioDataSize > 0) {
                mSize + (positionMs.toDouble() / totalDuration * audioDataSize).toLong()
            } else 0L
        } else 0L

        val flow = if (quality == 0) {
            songService.streamSong(songId, byteOffset)
        } else {
            songService.downloadSong(songId, quality, byteOffset, force = false)
        } ?: return null
        val rawStream = FlowInputStream(flow, sessionScope)

        val isOgg = metadata.size >= 4 &&
                metadata[0] == 'O'.code.toByte() && metadata[1] == 'g'.code.toByte() &&
                metadata[2] == 'g'.code.toByte() && metadata[3] == 'S'.code.toByte()

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

        sessionScope.launch(Dispatchers.IO) {
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
                            val info = runBlocking { infoDeferred.await() }
                            val shortBuffer =
                                convertToShortBuffer(pcm.data, pcm.len, info.bitsPerSample)
                            runBlocking { pcmChannel.send(shortBuffer) }
                        }
                    })
                    decoder.decode()
                } else {
                    val ogg = OggFile(stream)
                    val reader = ogg.getPacketReader()
                    var info: OpusInfo? = null
                    var decoder: OpusDecoder? = null

                    if (byteOffset > 0) {
                        val metaOgg = OggFile(metadata.inputStream())
                        val metaReader = metaOgg.getPacketReader()
                        var metaPacket = metaReader.getNextPacket()
                        while (metaPacket != null) {
                            val opusPacket = OpusPacketFactory.create(metaPacket)
                            if (opusPacket is OpusInfo) {
                                info = opusPacket
                                decoder = OpusDecoder(48000, opusPacket.numChannels)
                                infoDeferred.complete(
                                    PlaybackInfo(
                                        48000,
                                        opusPacket.numChannels,
                                        16
                                    )
                                )
                                break
                            }
                            metaPacket = metaReader.getNextPacket()
                        }
                    }

                    val samplesToBuffer = 48000 / 5
                    var bufferedSamples = 0
                    var buffer: ShortArray? = null
                    if (info != null) {
                        buffer = ShortArray(samplesToBuffer * info.numChannels)
                    }

                    var packet = reader.getNextPacket()
                    while (packet != null) {
                        when (val opusPacket = OpusPacketFactory.create(packet)) {
                            is OpusInfo -> {
                                if (info == null) {
                                    info = opusPacket
                                    decoder = OpusDecoder(48000, opusPacket.numChannels)
                                    infoDeferred.complete(
                                        PlaybackInfo(
                                            48000,
                                            opusPacket.numChannels,
                                            16
                                        )
                                    )
                                    buffer = ShortArray(samplesToBuffer * opusPacket.numChannels)
                                }
                            }
                            is OpusTags -> {}
                            else -> {
                                val currentDecoder = decoder
                                val currentInfo = info
                                if (currentDecoder != null && currentInfo != null) {
                                    if (buffer == null) {
                                        buffer = ShortArray(samplesToBuffer * currentInfo.numChannels)
                                    }
                                    val data = packet.data
                                    if (data.isNotEmpty()) {
                                        val maxSamplesPerChannel = 5760
                                        val pcm = ShortArray(maxSamplesPerChannel * currentInfo.numChannels)
                                        val decodedSamplesPerChannel = currentDecoder.decode(
                                            data, 0, data.size,
                                            pcm, 0, maxSamplesPerChannel, false
                                        )

                                        if (decodedSamplesPerChannel > 0) {
                                            val samplesWithChannels =
                                                decodedSamplesPerChannel * currentInfo.numChannels

                                            if (samplesWithChannels > buffer.size) {
                                                if (bufferedSamples > 0) {
                                                    val shortBuffer =
                                                        BufferUtils.createShortBuffer(bufferedSamples)
                                                    shortBuffer.put(buffer, 0, bufferedSamples)
                                                    pcmChannel.send(shortBuffer.flip())
                                                    bufferedSamples = 0
                                                }
                                                val shortBuffer =
                                                    BufferUtils.createShortBuffer(samplesWithChannels)
                                                shortBuffer.put(pcm, 0, samplesWithChannels)
                                                pcmChannel.send(shortBuffer.flip())
                                            } else {
                                                if (bufferedSamples + samplesWithChannels > buffer.size) {
                                                    val shortBuffer =
                                                        BufferUtils.createShortBuffer(bufferedSamples)
                                                    shortBuffer.put(buffer, 0, bufferedSamples)
                                                    pcmChannel.send(shortBuffer.flip())
                                                    bufferedSamples = 0
                                                }

                                                System.arraycopy(
                                                    pcm,
                                                    0,
                                                    buffer,
                                                    bufferedSamples,
                                                    samplesWithChannels
                                                )
                                                bufferedSamples += samplesWithChannels
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        packet = reader.getNextPacket()
                    }

                    if (bufferedSamples > 0 && buffer != null) {
                        val shortBuffer = BufferUtils.createShortBuffer(bufferedSamples)
                        shortBuffer.put(buffer, 0, bufferedSamples)
                        pcmChannel.send(shortBuffer.flip())
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("Decoder error for $songId: ${e.message}")
                    if (infoDeferred.isActive) infoDeferred.completeExceptionally(e)
                }
            } finally {
                pcmChannel.close()
                stream.close()
            }
        }

        return try {
            val info = withTimeout(5.seconds) { infoDeferred.await() }
            PlaybackSession(
                song = song,
                sampleRate = info.sampleRate,
                bitsPerSample = info.bitsPerSample,
                channels = info.channels,
                pcmFlow = flow {
                    for (buffer in pcmChannel) {
                        emit(buffer)
                    }
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun convertToShortBuffer(data: ByteArray, len: Int, bitsPerSample: Int): ShortBuffer {
        val bytesPerSample = bitsPerSample / 8
        val samplesCount = len / bytesPerSample
        val shortBuffer = BufferUtils.createShortBuffer(samplesCount)

        when (bitsPerSample) {
            16 -> {
                for (i in 0 until samplesCount) {
                    val lsb = data[i * 2].toInt() and 0xFF
                    val msb = data[i * 2 + 1].toInt()
                    shortBuffer.put(((msb shl 8) or lsb).toShort())
                }
            }

            24 -> {
                for (i in 0 until samplesCount) {
                    val mid = data[i * 3 + 1].toInt() and 0xFF
                    val msb = data[i * 3 + 2].toInt()
                    shortBuffer.put(((msb shl 8) or mid).toShort())
                }
            }

            8 -> {
                for (i in 0 until samplesCount) {
                    val s = data[i].toInt()
                    shortBuffer.put((s shl 8).toShort())
                }
            }
        }

        return shortBuffer.flip()
    }

    private class FlowInputStream(
        private val flow: Flow<ByteArray>,
        scope: CoroutineScope
    ) : InputStream() {
        private val channel = Channel<ByteArray>(64)
        private var currentBuffer: ByteArray? = null
        private var bufferOffset = 0
        private var isClosed = false
        private val job: Job = scope.launch(Dispatchers.IO) {
            try {
                flow.collect {
                    if (isClosed) throw CancellationException()
                    channel.send(it)
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val r = read(b, 0, 1)
            return if (r == -1) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed) return -1
            if (currentBuffer == null || bufferOffset >= currentBuffer!!.size) {
                currentBuffer = try {
                    runBlocking { channel.receiveCatching().getOrNull() }
                } catch (_: Exception) {
                    null
                }
                bufferOffset = 0
                if (currentBuffer == null) return -1
            }

            val remaining = currentBuffer!!.size - bufferOffset
            val toRead = min(len, remaining)
            System.arraycopy(currentBuffer!!, bufferOffset, b, off, toRead)
            bufferOffset += toRead
            return toRead
        }

        override fun close() {
            if (!isClosed) {
                isClosed = true
                job.cancel()
                channel.close()
            }
            super.close()
        }
    }
}
