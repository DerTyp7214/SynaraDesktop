package dev.dertyp.synara.player.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AudioProbeTest {
    private class BytesSource(
        private val bytes: ByteArray,
        override val cacheKey: String = "test",
        override val formatHint: String? = null
    ) : AudioSource {
        val opens = mutableListOf<Long>()
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            if (offset > bytes.size) return null
            return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    private fun probe(bytes: ByteArray, probe: AudioProbe = AudioProbe()): ProbeInfo? = runBlocking {
        probe.probe(BytesSource(bytes))
    }

    private fun id3(payloadSize: Int, footer: Boolean = false): ByteArray {
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0,
            if (footer) 0x10 else 0,
            ((payloadSize shr 21) and 0x7F).toByte(),
            ((payloadSize shr 14) and 0x7F).toByte(),
            ((payloadSize shr 7) and 0x7F).toByte(),
            (payloadSize and 0x7F).toByte(),
        )
        return header + ByteArray(payloadSize) { 0x55 } + if (footer) ByteArray(10) { 0x33 } else ByteArray(0)
    }

    private fun flac(): ByteArray {
        val streamInfo = ByteArray(34)
        streamInfo[10] = 0x0A
        streamInfo[11] = 0xC4.toByte()
        streamInfo[12] = 0x42
        streamInfo[13] = 0xF0.toByte()
        streamInfo[14] = 0x00
        streamInfo[15] = 0x01
        streamInfo[16] = 0x58
        streamInfo[17] = 0x88.toByte()
        return Mp4Fixtures.ascii("fLaC") + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo + ByteArray(64) { 0x11 }
    }

    private fun oggPage(packet: ByteArray): ByteArray {
        val header = Mp4Fixtures.ascii("OggS") + byteArrayOf(0, 2) + ByteArray(20) + byteArrayOf(1, packet.size.toByte())
        return header + packet + ByteArray(64)
    }

    private fun wav(): ByteArray {
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val fmt = le16(1) + le16(2) + le32(44100) + le32(44100 * 4) + le16(4) + le16(16)
        val data = ByteArray(4000)
        return Mp4Fixtures.ascii("RIFF") + le32(36 + data.size) + Mp4Fixtures.ascii("WAVE") +
                Mp4Fixtures.ascii("fmt ") + le32(16) + fmt + Mp4Fixtures.ascii("data") + le32(data.size) + data
    }

    private fun adts(frames: Int): ByteArray {
        val frameLength = 200
        val frame = ByteArray(frameLength)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xF1.toByte()
        frame[2] = ((1 shl 6) or (4 shl 2) or 0).toByte()
        frame[3] = ((2 shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        frame[4] = ((frameLength shr 3) and 0xFF).toByte()
        frame[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        frame[6] = 0xFC.toByte()
        val out = ByteArray(frameLength * frames)
        for (i in 0 until frames) frame.copyInto(out, i * frameLength)
        return out
    }

    @Test
    fun detectsFlacAndReadsDuration() {
        val info = assertIs<ProbeInfo.Flac>(probe(flac()))
        assertEquals(4 + 4 + 34, info.metadata.size)
        assertEquals(2000L, info.durationMs)
    }

    @Test
    fun detectsOggOpusAndRejectsVorbis() {
        val opusHead = Mp4Fixtures.ascii("OpusHead") + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val opus = assertIs<ProbeInfo.OggOpus>(probe(oggPage(opusHead)))
        assertEquals('O'.code.toByte(), opus.metadata[0])
        val vorbis = byteArrayOf(1) + Mp4Fixtures.ascii("vorbis") + ByteArray(23)
        assertFailsWith<UnsupportedAudioFormatException> { probe(oggPage(vorbis)) }
    }

    @Test
    fun detectsWav() {
        val info = assertIs<ProbeInfo.Pcm>(probe(wav()))
        assertEquals(44100, info.header.sampleRate)
        assertEquals(2, info.header.channels)
        assertEquals(44L, info.header.dataStart)
    }

    @Test
    fun detectsMp4() {
        val fixture = Mp4Fixtures.build(moovFirst = true)
        val info = assertIs<ProbeInfo.Mp4>(probe(fixture.bytes))
        assertEquals(10, info.track.sampleCount)
    }

    @Test
    fun detectsMp4WithMoovAtEnd() {
        val fixture = Mp4Fixtures.build(moovFirst = false, mdatPadding = 800_000)
        val source = BytesSource(fixture.bytes)
        val info = assertIs<ProbeInfo.Mp4>(runBlocking { AudioProbe().probe(source) })
        assertEquals(fixture.mdatEnd, source.opens.last())
        assertEquals(10, info.track.sampleCount)
    }

    @Test
    fun detectsAdts() {
        val info = assertIs<ProbeInfo.Adts>(probe(adts(10)))
        assertEquals(0L, info.audioStart)
        assertEquals(44100, info.header.sampleRate)
        assertEquals(2, info.header.channelConfig)
        assertEquals(1, info.header.profile)
        assertEquals(200.0, info.averageFrameBytes)
    }

    @Test
    fun detectsMp3AfterSmallId3Tag() {
        val tag = id3(300)
        val bytes = tag + Mp3Fixtures.xingFrame(frames = 100, bytes = 41700) + Mp3Fixtures.frames(4)
        val source = BytesSource(bytes)
        val info = assertIs<ProbeInfo.Mp3>(runBlocking { AudioProbe().probe(source) })
        assertEquals(listOf(0L), source.opens)
        assertEquals(tag.size.toLong(), info.firstFrameOffset)
        assertEquals(tag.size.toLong() + Mp3Fixtures.FRAME_LENGTH, info.audioStart)
        assertEquals(100L, assertNotNull(info.xing).frames)
    }

    @Test
    fun reopensAfterLargeId3TagWithFooter() {
        val tag = id3(20_000, footer = true)
        val bytes = tag + Mp3Fixtures.frames(6)
        val source = BytesSource(bytes)
        val info = assertIs<ProbeInfo.Mp3>(runBlocking { AudioProbe().probe(source) })
        assertEquals(listOf(0L, tag.size.toLong()), source.opens)
        assertEquals(tag.size.toLong(), info.firstFrameOffset)
        assertNull(info.xing)
        assertEquals(info.firstFrameOffset, info.audioStart)
    }

    @Test
    fun findsMp3AfterJunk() {
        val bytes = ByteArray(123) { 0x20 } + Mp3Fixtures.frames(5)
        val info = assertIs<ProbeInfo.Mp3>(probe(bytes))
        assertEquals(123L, info.firstFrameOffset)
    }

    @Test
    fun rejectsUnknownData() {
        assertFailsWith<UnsupportedAudioFormatException> { probe(ByteArray(5000) { (it * 7).toByte() }) }
    }

    @Test
    fun cachesByKey() {
        val probe = AudioProbe()
        val first = BytesSource(flac(), cacheKey = "same")
        val second = BytesSource(Mp3Fixtures.frames(5), cacheKey = "same")
        runBlocking {
            assertIs<ProbeInfo.Flac>(probe.probe(first))
            assertIs<ProbeInfo.Flac>(probe.probe(second))
        }
        assertEquals(emptyList(), second.opens)
    }
}
