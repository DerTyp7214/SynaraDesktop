package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.AiffHeader
import dev.dertyp.synara.player.audio.OpusHead
import dev.dertyp.synara.player.audio.PcmHeader
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusMSDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
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
            var foundPcm = false
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
                if (PcmHeader.isWav(initialBuffer, i) || AiffHeader.isAiff(initialBuffer, i)) {
                    foundPcm = true
                    magicOffset = i
                    break
                }
            }

            if (foundPcm) {
                val headerStream = SequenceInputStream(
                    initialBuffer.sliceArray(magicOffset until firstRead).inputStream(),
                    stream
                )
                val pcmHeader = PcmHeader.parseFromStream(headerStream, magicOffset.toLong())
                stream.close()
                return@withContext pcmHeader?.encode()?.also { encoded ->
                    cacheMutex.withLock {
                        metadataCache[cacheKey] = encoded
                    }
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

    private fun readExactly(input: InputStream, buffer: ByteArray, length: Int = buffer.size): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, totalRead, length - totalRead)
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
        val startMs: Long,
        val pcmFlow: Flow<ShortBuffer>
    )

    private fun effectiveStartMs(song: UserSong, positionMs: Long): Long {
        if (positionMs > 0) return positionMs
        val lead = song.audioStartMs ?: return 0L
        if (lead <= 0) return 0L
        return if (song.duration > 0) lead.coerceAtMost(song.duration - 1) else lead
    }

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
        val startMs = effectiveStartMs(song, positionMs)

        PcmHeader.decode(metadata)?.let { pcmHeader ->
            return createPcmPlaybackSession(song, songId, pcmHeader, startMs, sessionScope)
        }

        val totalDuration = song.duration
        val mSize = metadata.size.toLong()

        val quality = settings.get(SettingKey.StreamingQuality, 0)

        val byteOffset = if (startMs > 0 && totalDuration > 0) {
            val knownSize = song.effectiveAudio?.fileSize ?: 0L
            val fileSize = if (quality == 0) {
                if (knownSize > 0) knownSize else songService.getStreamSize(songId)
            } else {
                val size = songService.getDownloadSize(songId, quality, force = false)
                if (size > 0) size
                else if (knownSize > 0) knownSize
                else songService.getStreamSize(songId)
            }
            val audioDataSize = fileSize - mSize
            if (audioDataSize > 0) {
                mSize + (startMs.toDouble() / totalDuration * audioDataSize).toLong()
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
                startMs = startMs,
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

    private suspend fun createPcmPlaybackSession(
        song: UserSong,
        songId: PlatformUUID,
        header: PcmHeader,
        positionMs: Long,
        sessionScope: CoroutineScope
    ): PlaybackSession? {
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

        val flow = songService.streamSong(songId, byteOffset) ?: return null
        val rawStream = FlowInputStream(flow, sessionScope)

        val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)

        sessionScope.launch(Dispatchers.IO) {
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
                if (e !is CancellationException) {
                    println("PCM decoder error for $songId: ${e.message}")
                }
            } finally {
                pcmChannel.close()
                rawStream.close()
            }
        }

        return PlaybackSession(
            song = song,
            sampleRate = header.sampleRate,
            bitsPerSample = header.bitsPerSample,
            channels = header.channels,
            startMs = positionMs,
            pcmFlow = flow {
                for (buffer in pcmChannel) {
                    emit(buffer)
                }
            }
        )
    }

    private fun convertToShortBuffer(
        data: ByteArray,
        len: Int,
        bitsPerSample: Int,
        isFloat: Boolean = false,
        unsigned8Bit: Boolean = false
    ): ShortBuffer {
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

            32 -> {
                if (isFloat) {
                    for (i in 0 until samplesCount) {
                        val bits = (data[i * 4].toInt() and 0xFF) or
                                ((data[i * 4 + 1].toInt() and 0xFF) shl 8) or
                                ((data[i * 4 + 2].toInt() and 0xFF) shl 16) or
                                ((data[i * 4 + 3].toInt() and 0xFF) shl 24)
                        val sample = Float.fromBits(bits).coerceIn(-1f, 1f)
                        shortBuffer.put((sample * Short.MAX_VALUE).toInt().toShort())
                    }
                } else {
                    for (i in 0 until samplesCount) {
                        val mid = data[i * 4 + 2].toInt() and 0xFF
                        val msb = data[i * 4 + 3].toInt()
                        shortBuffer.put(((msb shl 8) or mid).toShort())
                    }
                }
            }

            8 -> {
                if (unsigned8Bit) {
                    for (i in 0 until samplesCount) {
                        val s = (data[i].toInt() and 0xFF) - 128
                        shortBuffer.put((s shl 8).toShort())
                    }
                } else {
                    for (i in 0 until samplesCount) {
                        val s = data[i].toInt()
                        shortBuffer.put((s shl 8).toShort())
                    }
                }
            }
        }

        return shortBuffer.flip()
    }

    private class OpusStream(head: OpusHead, applyPreSkip: Boolean) {
        val channels = head.channels
        val output = ShortArray(MAX_PACKET_FRAMES * head.channels)

        private val order = head.nativeChannelOrder
        private val scratch = ShortArray(MAX_PACKET_FRAMES * head.channels)
        private val singleStream = if (head.isMultistream) null else OpusDecoder(48000, head.channels)
        private val multiStream = if (head.isMultistream) {
            OpusMSDecoder.create(
                48000,
                head.channels,
                head.streamCount,
                head.coupledStreamCount,
                head.channelMapping
            )
        } else null

        private var remainingPreSkip = if (applyPreSkip) head.preSkip else 0

        init {
            singleStream?.setGain(head.outputGain)
            multiStream?.setGain(head.outputGain)
        }

        fun decode(data: ByteArray): Int {
            val frames = multiStream?.decodeMultistream(data, 0, data.size, scratch, 0, MAX_PACKET_FRAMES, 0)
                ?: singleStream?.decode(data, 0, data.size, scratch, 0, MAX_PACKET_FRAMES, false)
                ?: 0
            if (frames <= 0) return 0

            val skipped = min(remainingPreSkip, frames)
            remainingPreSkip -= skipped
            val kept = frames - skipped
            if (kept <= 0) return 0

            val currentOrder = order
            if (currentOrder == null) {
                System.arraycopy(scratch, skipped * channels, output, 0, kept * channels)
            } else {
                for (frame in 0 until kept) {
                    val source = (skipped + frame) * channels
                    val target = frame * channels
                    for (channel in 0 until channels) {
                        output[target + channel] = scratch[source + currentOrder[channel]]
                    }
                }
            }
            return kept * channels
        }

        companion object {
            private const val MAX_PACKET_FRAMES = 5760
        }
    }

    private class PcmAccumulator(
        channels: Int,
        private val target: SendChannel<ShortBuffer>
    ) {
        private val buffer = ShortArray(FRAMES_PER_CHUNK * channels)
        private var count = 0

        suspend fun append(data: ShortArray, length: Int) {
            if (length <= 0) return
            if (length > buffer.size) {
                flush()
                send(data, length)
                return
            }
            if (count + length > buffer.size) flush()
            System.arraycopy(data, 0, buffer, count, length)
            count += length
        }

        suspend fun flush() {
            if (count <= 0) return
            send(buffer, count)
            count = 0
        }

        private suspend fun send(data: ShortArray, length: Int) {
            val shortBuffer = BufferUtils.createShortBuffer(length)
            shortBuffer.put(data, 0, length)
            target.send(shortBuffer.flip())
        }

        companion object {
            private const val FRAMES_PER_CHUNK = 48000 / 5
        }
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
