package dev.dertyp.synara.player.audio

import dev.dertyp.synara.player.audio.decode.AdtsHeader
import dev.dertyp.synara.player.audio.decode.Mp3Header
import dev.dertyp.synara.player.audio.decode.Mp4Demux
import dev.dertyp.synara.player.audio.decode.VbriInfo
import dev.dertyp.synara.player.audio.decode.XingInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream

class AudioProbe(private val cacheSize: Int = 50) {
    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<String, ProbeInfo>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProbeInfo>?): Boolean =
            size > cacheSize
    }

    suspend fun probe(source: AudioSource): ProbeInfo? = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            cache[source.cacheKey]?.let { return@withContext it }
        }
        val info = try {
            probeUncached(source, this)
        } catch (e: UnsupportedAudioFormatException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (info != null) {
            cacheMutex.withLock { cache[source.cacheKey] = info }
        }
        info
    }

    private suspend fun probeUncached(source: AudioSource, scope: CoroutineScope): ProbeInfo? {
        var base = 0L
        var stream = source.open(0, scope) ?: return null
        try {
            val buffer = ByteArray(PROBE_BYTES)
            var read = readExactly(stream, buffer)
            var skip = 0
            while (read - skip >= 10) {
                val tagSize = Id3v2.tagSize(buffer, skip) ?: break
                if (skip + tagSize < read) {
                    skip += tagSize
                } else {
                    val tagEnd = base + skip + tagSize
                    stream.close()
                    stream = source.open(tagEnd, scope) ?: return null
                    base = tagEnd
                    read = readExactly(stream, buffer)
                    skip = 0
                }
            }
            if (read - skip < 4) return null
            val data = buffer.copyOfRange(skip, read)
            return sniff(data, base + skip, stream, source, scope)
        } finally {
            stream.close()
        }
    }

    private suspend fun sniff(
        data: ByteArray,
        dataBase: Long,
        rest: InputStream,
        source: AudioSource,
        scope: CoroutineScope
    ): ProbeInfo? {
        detectAt(data, 0, dataBase, rest, source, scope)?.let { return it }
        if (data.size >= 8 && data.matches(4, "ftyp")) {
            val track = Mp4Demux.read(SequenceInputStream(data.inputStream(), rest), dataBase) { offset ->
                source.open(offset, scope)
            }
            return ProbeInfo.Mp4(track)
        }
        adtsAt(data, 0, dataBase)?.let { return it }
        mp3At(data, 0, dataBase)?.let { return it }

        for (i in 1 until data.size - 3) {
            detectAt(data, i, dataBase, rest, source, scope)?.let { return it }
        }
        for (i in 1 until data.size - 3) {
            mp3At(data, i, dataBase)?.let { return it }
        }
        throw UnsupportedAudioFormatException(source.formatHint)
    }

    private fun detectAt(
        data: ByteArray,
        offset: Int,
        dataBase: Long,
        rest: InputStream,
        source: AudioSource,
        scope: CoroutineScope
    ): ProbeInfo? {
        if (data.matches(offset, "fLaC")) return flac(data, offset, rest)
        if (data.matches(offset, "OggS")) return ogg(data, offset)
        if (PcmHeader.isWav(data, offset) || AiffHeader.isAiff(data, offset)) {
            val headerStream = SequenceInputStream(data.inputStream(offset, data.size - offset), rest)
            val header = PcmHeader.parseFromStream(headerStream, dataBase + offset)
                ?: throw UnsupportedAudioFormatException(source.formatHint ?: "pcm")
            return ProbeInfo.Pcm(header)
        }
        return null
    }

    private fun flac(data: ByteArray, offset: Int, rest: InputStream): ProbeInfo? {
        val bis = SequenceInputStream(data.inputStream(offset, data.size - offset), rest)
        val bos = ByteArrayOutputStream()
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
                val block = ByteArray(length)
                if (readExactly(bis, block) != length) break
                bos.write(block)
            }
        }
        val metadata = bos.toByteArray()
        return ProbeInfo.Flac(metadata, flacDurationMs(metadata))
    }

    private fun ogg(data: ByteArray, offset: Int): ProbeInfo {
        if (offset + 27 <= data.size) {
            val segments = data.u8(offset + 26)
            val packet = offset + 27 + segments
            if (data.matches(packet, "\u0001vorbis")) throw UnsupportedAudioFormatException("ogg vorbis")
            if (packet + 8 <= data.size && !data.matches(packet, "OpusHead")) {
                throw UnsupportedAudioFormatException("ogg")
            }
        }
        return ProbeInfo.OggOpus(data.copyOfRange(offset, data.size))
    }

    private fun adtsAt(data: ByteArray, offset: Int, dataBase: Long): ProbeInfo? {
        val header = AdtsHeader.parseConfirmed(data, offset) ?: return null
        var position = offset
        var frames = 0
        var bytes = 0L
        while (true) {
            val frame = AdtsHeader.parse(data, position) ?: break
            if (!frame.isCompatible(header) || position + frame.frameLength > data.size) break
            frames++
            bytes += frame.frameLength
            position += frame.frameLength
        }
        val average = if (frames > 0) bytes.toDouble() / frames else header.frameLength.toDouble()
        return ProbeInfo.Adts(dataBase + offset, header, average)
    }

    private fun mp3At(data: ByteArray, offset: Int, dataBase: Long): ProbeInfo? {
        val header = Mp3Header.parseConfirmed(data, offset) ?: return null
        val xing = if (header.layer == 3) XingInfo.parse(data, offset, header) else null
        val vbri = if (xing == null && header.layer == 3) VbriInfo.parse(data, offset) else null
        return ProbeInfo.Mp3(dataBase + offset, header, xing, vbri)
    }

    private fun flacDurationMs(metadata: ByteArray): Long? {
        if (metadata.size < 8 + 18) return null
        if (metadata.u8(4) and 0x7F != 0) return null
        val p = 8
        val sampleRate = (metadata.u8(p + 10) shl 12) or (metadata.u8(p + 11) shl 4) or (metadata.u8(p + 12) shr 4)
        val totalSamples = ((metadata.u8(p + 13) and 0x0F).toLong() shl 32) or metadata.u32(p + 14)
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return totalSamples * 1000 / sampleRate
    }

    companion object {
        private const val PROBE_BYTES = 8192
    }
}

object Id3v2 {
    fun tagSize(bytes: ByteArray, offset: Int): Int? {
        if (offset + 10 > bytes.size || !bytes.matches(offset, "ID3")) return null
        val major = bytes.u8(offset + 3)
        if (major == 0xFF || bytes.u8(offset + 4) == 0xFF) return null
        var size = 0
        for (i in 6 until 10) {
            val b = bytes.u8(offset + i)
            if (b and 0x80 != 0) return null
            size = (size shl 7) or b
        }
        val footer = if (bytes.u8(offset + 5) and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }
}
