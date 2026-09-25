package dev.dertyp.synara.player.audio

import dev.dertyp.synara.player.audio.decode.Mp4Demux
import dev.dertyp.synara.player.audio.decode.Mp4Track
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Mp4DemuxTest {
    private fun readTrack(file: ByteArray, opens: MutableList<Long> = mutableListOf()) = runBlocking {
        Mp4Demux.read(ByteArrayInputStream(file), 0L) { offset ->
            opens.add(offset)
            ByteArrayInputStream(file, offset.toInt(), file.size - offset.toInt())
        }
    }

    @Test
    fun readsMoovBeforeMdat() {
        val fixture = Mp4Fixtures.build(moovFirst = true)
        val opens = mutableListOf<Long>()
        val track = readTrack(fixture.bytes, opens)
        assertEquals(emptyList(), opens)
        fixture.assertMatches(track)
    }

    @Test
    fun reopensAfterLargeMdatToFindMoov() {
        val fixture = Mp4Fixtures.build(moovFirst = false, mdatPadding = 700_000)
        val opens = mutableListOf<Long>()
        val track = readTrack(fixture.bytes, opens)
        assertEquals(listOf(fixture.mdatEnd), opens)
        fixture.assertMatches(track)
    }

    @Test
    fun skipsSmallMdatInline() {
        val fixture = Mp4Fixtures.build(moovFirst = false)
        val opens = mutableListOf<Long>()
        val track = readTrack(fixture.bytes, opens)
        assertEquals(emptyList(), opens)
        fixture.assertMatches(track)
    }

    @Test
    fun handlesLargesizeAndCo64() {
        val fixture = Mp4Fixtures.build(moovFirst = false, largeMdat = true, co64 = true, mdatPadding = 600_000)
        val opens = mutableListOf<Long>()
        val track = readTrack(fixture.bytes, opens)
        assertEquals(listOf(fixture.mdatEnd), opens)
        fixture.assertMatches(track)
    }

    @Test
    fun seeksBySampleTime() {
        val fixture = Mp4Fixtures.build(moovFirst = true)
        val track = readTrack(fixture.bytes)
        assertEquals(0, track.sampleAt(0))
        assertEquals(0, track.sampleAt(1023))
        assertEquals(1, track.sampleAt(1024))
        assertEquals(5, track.sampleAt(5 * 1024 + 10))
        assertEquals(track.sampleCount - 1, track.sampleAt(Long.MAX_VALUE))
    }

    @Test
    fun rejectsFragmentedFiles() {
        val out = ByteArrayOutputStream()
        out.write(Mp4Fixtures.box("ftyp", Mp4Fixtures.ascii("M4A ") + ByteArray(4)))
        out.write(Mp4Fixtures.box("moof", ByteArray(16)))
        val bytes = out.toByteArray()
        assertFailsWith<UnsupportedAudioFormatException> { readTrack(bytes) }
    }
}

object Mp4Fixtures {
    const val TIMESCALE = 44100
    val ASC = byteArrayOf(0x12, 0x10)
    val SAMPLE_SIZES = intArrayOf(100, 120, 90, 110, 130, 95, 105, 115, 125, 85)
    val SAMPLES_PER_CHUNK = intArrayOf(4, 4, 2)

    class Fixture(val bytes: ByteArray, val sampleOffsets: LongArray, val mdatEnd: Long) {
        fun assertMatches(track: Mp4Track) {
            assertEquals(TIMESCALE.toLong(), track.timescale)
            assertEquals(44100, track.sampleRate)
            assertEquals(2, track.channels)
            assertEquals(0x40, track.objectType)
            assertContentEquals(ASC, track.audioSpecificConfig)
            assertContentEquals(SAMPLE_SIZES, track.sizes)
            assertContentEquals(sampleOffsets, track.offsets)
            assertContentEquals(LongArray(SAMPLE_SIZES.size) { it * 1024L }, track.times)
            assertEquals(SAMPLE_SIZES.size * 1024L * 1000 / TIMESCALE, track.durationMs)
        }
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    fun int32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    fun int64(value: Long): ByteArray = int32(value ushr 32) + int32(value and 0xFFFFFFFFL)

    fun int16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    fun box(type: String, payload: ByteArray): ByteArray = int32(8L + payload.size) + ascii(type) + payload

    fun fullBox(type: String, payload: ByteArray): ByteArray = box(type, ByteArray(4) + payload)

    private fun esds(): ByteArray {
        val dsi = byteArrayOf(0x05, ASC.size.toByte()) + ASC
        val dcd = byteArrayOf(0x04, (13 + dsi.size).toByte(), 0x40, 0x15) + ByteArray(11) + dsi
        val es = byteArrayOf(0x03, (3 + dcd.size + 3).toByte(), 0, 1, 0) + dcd + byteArrayOf(0x06, 0x01, 0x02)
        return fullBox("esds", es)
    }

    private fun stsd(): ByteArray {
        val entryBody = ByteArray(6) + int16(1) + ByteArray(8) + int16(2) + int16(16) + ByteArray(4) +
                int32(TIMESCALE.toLong() shl 16) + esds()
        return fullBox("stsd", int32(1) + box("mp4a", entryBody))
    }

    private fun moov(chunkOffsets: LongArray, co64: Boolean): ByteArray {
        val stts = fullBox("stts", int32(1) + int32(SAMPLE_SIZES.size.toLong()) + int32(1024))
        val stsc = fullBox(
            "stsc",
            int32(2) + int32(1) + int32(4) + int32(1) + int32(3) + int32(2) + int32(1)
        )
        var stszPayload = int32(0) + int32(SAMPLE_SIZES.size.toLong())
        SAMPLE_SIZES.forEach { stszPayload += int32(it.toLong()) }
        val stsz = fullBox("stsz", stszPayload)
        var chunkPayload = int32(chunkOffsets.size.toLong())
        chunkOffsets.forEach { chunkPayload += if (co64) int64(it) else int32(it) }
        val stco = fullBox(if (co64) "co64" else "stco", chunkPayload)
        val stbl = box("stbl", stsd() + stts + stsc + stsz + stco)
        val minf = box("minf", box("smhd", ByteArray(8)) + stbl)
        val mdhd = fullBox("mdhd", int32(0) + int32(0) + int32(TIMESCALE.toLong()) + int32(SAMPLE_SIZES.size * 1024L) + ByteArray(4))
        val hdlr = fullBox("hdlr", int32(0) + ascii("soun") + ByteArray(12) + byteArrayOf(0))
        val mdia = box("mdia", mdhd + hdlr + minf)
        val videoTrak = box("trak", box("mdia", fullBox("hdlr", int32(0) + ascii("vide") + ByteArray(13))))
        return box("moov", fullBox("mvhd", ByteArray(96)) + videoTrak + box("trak", box("tkhd", ByteArray(84)) + mdia))
    }

    fun build(
        moovFirst: Boolean,
        mdatPadding: Int = 0,
        largeMdat: Boolean = false,
        co64: Boolean = false
    ): Fixture {
        val ftyp = box("ftyp", ascii("M4A ") + int32(0) + ascii("isomM4A "))
        val sampleData = ByteArray(SAMPLE_SIZES.sum() + mdatPadding) { (it % 251).toByte() }
        val mdatHeaderSize = if (largeMdat) 16 else 8
        val mdatSize = mdatHeaderSize.toLong() + sampleData.size

        fun layout(moovSize: Int): Pair<Long, Long> {
            val mdatStart = if (moovFirst) ftyp.size.toLong() + moovSize else ftyp.size.toLong()
            return mdatStart to mdatStart + mdatHeaderSize
        }

        val placeholder = moov(LongArray(SAMPLES_PER_CHUNK.size), co64)
        val (mdatStart, dataStart) = layout(placeholder.size)
        val chunkOffsets = LongArray(SAMPLES_PER_CHUNK.size)
        val sampleOffsets = LongArray(SAMPLE_SIZES.size)
        var position = dataStart
        var sample = 0
        for (chunk in SAMPLES_PER_CHUNK.indices) {
            chunkOffsets[chunk] = position
            repeat(SAMPLES_PER_CHUNK[chunk]) {
                sampleOffsets[sample] = position
                position += SAMPLE_SIZES[sample]
                sample++
            }
        }
        val moov = moov(chunkOffsets, co64)
        val mdat = if (largeMdat) {
            int32(1) + ascii("mdat") + int64(mdatSize) + sampleData
        } else {
            int32(mdatSize) + ascii("mdat") + sampleData
        }
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        if (moovFirst) {
            out.write(moov)
            out.write(mdat)
        } else {
            out.write(mdat)
            out.write(moov)
        }
        return Fixture(out.toByteArray(), sampleOffsets, mdatStart + mdatSize)
    }

    fun stream(bytes: ByteArray, offset: Long): InputStream =
        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
}
