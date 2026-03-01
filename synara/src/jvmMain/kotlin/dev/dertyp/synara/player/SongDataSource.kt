package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import org.lwjgl.BufferUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ShortBuffer
import kotlin.math.min

class SongDataSource(
    private val songService: ISongService,
    private val songCache: SongCache
) {
    @Suppress("PrivatePropertyName")
    private val MAX_METADATA_CACHE_SIZE = 50
    private val cacheMutex = Mutex()

    private val metadataCache =
        object : LinkedHashMap<PlatformUUID, ByteArray>(MAX_METADATA_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlatformUUID, ByteArray>?): Boolean =
                size > MAX_METADATA_CACHE_SIZE
        }

    suspend fun getSong(songId: PlatformUUID): UserSong? {
        return songCache.get(songId) ?: songService.byId(songId)?.also { song ->
            songCache.put(song)
        }
    }

    suspend fun getMetadata(songId: PlatformUUID): ByteArray? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            metadataCache[songId]?.let { return@withContext it }
        }

        try {
            val flow = songService.streamSong(songId, 0) ?: return@withContext null
            val stream = FlowInputStream(flow, this)
            val bos = ByteArrayOutputStream()

            var foundMagic = false
            for (i in 0 until 65536) {
                val b = stream.read()
                if (b == -1) break
                if (b == 'f'.code) {
                    val b2 = stream.read()
                    val b3 = stream.read()
                    val b4 = stream.read()
                    if (b2 == 'L'.code && b3 == 'a'.code && b4 == 'C'.code) {
                        foundMagic = true
                        break
                    }
                }
            }

            if (!foundMagic) {
                stream.close()
                return@withContext null
            }

            bos.write(
                byteArrayOf(
                    'f'.code.toByte(), 'L'.code.toByte(),
                    'a'.code.toByte(), 'C'.code.toByte()
                )
            )

            var lastBlock = false
            while (!lastBlock) {
                val header = ByteArray(4)
                if (readExactly(stream, header) != 4) break
                bos.write(header)

                lastBlock = (header[0].toInt() and 0x80) != 0
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

                if (length > 0) {
                    val data = ByteArray(length)
                    if (readExactly(stream, data) != length) break
                    bos.write(data)
                }
            }
            stream.close()

            val metadata = bos.toByteArray()
            if (metadata.isNotEmpty()) {
                cacheMutex.withLock {
                    metadataCache[songId] = metadata
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

    suspend fun createPlaybackSession(
        songId: PlatformUUID,
        positionMs: Long,
        sessionScope: CoroutineScope
    ): PlaybackSession? {
        val song = getSong(songId) ?: return null
        val metadata = getMetadata(songId) ?: return null

        val totalDuration = song.duration
        val mSize = metadata.size.toLong()

        val byteOffset = if (positionMs > 0 && totalDuration > 0) {
            val fileSize =
                if (song.fileSize > 0) song.fileSize else songService.getStreamSize(songId)
            val audioDataSize = fileSize - mSize
            if (audioDataSize > 0) {
                mSize + (positionMs.toDouble() / totalDuration * audioDataSize).toLong()
            } else 0L
        } else 0L

        val flow = songService.streamSong(songId, byteOffset) ?: return null
        val rawStream = FlowInputStream(flow, sessionScope)

        val stream = if (byteOffset > 0) {
            val syncStream = object : InputStream() {
                private var foundSync = false
                private var pushback = -1

                override fun read(): Int {
                    if (pushback != -1) {
                        val b = pushback
                        pushback = -1
                        return b
                    }
                    if (foundSync) return rawStream.read()

                    var b = rawStream.read()
                    while (b != -1) {
                        if (b == 0xFF) {
                            val b2 = rawStream.read()
                            if (b2 == -1) return -1
                            if ((b2 and 0xFC) == 0xF8) {
                                foundSync = true
                                pushback = b2
                                return 0xFF
                            }
                            b = b2
                        } else {
                            b = rawStream.read()
                        }
                    }
                    return -1
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len <= 0) return 0
                    if (foundSync && pushback == -1) return rawStream.read(b, off, len)
                    val next = read()
                    if (next == -1) return -1
                    b[off] = next.toByte()
                    return 1
                }

                override fun close() = rawStream.close()
            }
            SequenceInputStream(metadata.inputStream(), syncStream)
        } else {
            rawStream
        }

        val pcmChannel = Channel<ShortBuffer>(Channel.BUFFERED)
        val infoDeferred = CompletableDeferred<StreamInfo>()

        sessionScope.launch(Dispatchers.IO) {
            try {
                val decoder = FLACDecoder(stream)
                decoder.addPCMProcessor(object : PCMProcessor {
                    override fun processStreamInfo(info: StreamInfo) {
                        infoDeferred.complete(info)
                    }

                    override fun processPCM(pcm: ByteData) {
                        val info = runBlocking { infoDeferred.await() }
                        val shortBuffer =
                            convertToShortBuffer(pcm.data, pcm.len, info.bitsPerSample)
                        runBlocking { pcmChannel.send(shortBuffer) }
                    }
                })
                decoder.decode()
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
            val info = withTimeout(5000) { infoDeferred.await() }
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
