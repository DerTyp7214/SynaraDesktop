package dev.dertyp.synara.player.audio

import dev.dertyp.synara.player.audio.decode.Mp3Header
import dev.dertyp.synara.player.audio.decode.VbriInfo
import dev.dertyp.synara.player.audio.decode.XingInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp3HeaderTest {
    @Test
    fun parsesMpeg1Layer3() {
        val header = assertNotNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64), 0))
        assertEquals(Mp3Header.Version.MPEG1, header.version)
        assertEquals(3, header.layer)
        assertEquals(128, header.bitRateKbps)
        assertEquals(44100, header.sampleRate)
        assertEquals(2, header.channels)
        assertEquals(1152, header.samplesPerFrame)
        assertEquals(417, header.frameLength)
        assertEquals(32, header.sideInfoLength)
    }

    @Test
    fun parsesPaddingAndMpeg2() {
        val padded = assertNotNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x92.toByte(), 0x64), 0))
        assertEquals(418, padded.frameLength)

        val mpeg2 = assertNotNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x84.toByte(), 0xC4.toByte()), 0))
        assertEquals(Mp3Header.Version.MPEG2, mpeg2.version)
        assertEquals(64, mpeg2.bitRateKbps)
        assertEquals(24000, mpeg2.sampleRate)
        assertEquals(1, mpeg2.channels)
        assertEquals(576, mpeg2.samplesPerFrame)
        assertEquals(192, mpeg2.frameLength)
        assertEquals(9, mpeg2.sideInfoLength)
    }

    @Test
    fun parsesMpeg25() {
        val header = assertNotNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xE3.toByte(), 0x80.toByte(), 0xC0.toByte()), 0))
        assertEquals(Mp3Header.Version.MPEG25, header.version)
        assertEquals(3, header.layer)
        assertEquals(64, header.bitRateKbps)
        assertEquals(11025, header.sampleRate)
        assertEquals(1, header.channels)
        assertEquals(576, header.samplesPerFrame)
        assertEquals(417, header.frameLength)
    }

    @Test
    fun rejectsInvalidHeaders() {
        assertNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0x64), 0))
        assertNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x0C, 0x64), 0))
        assertNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x9C.toByte(), 0x64), 0))
        assertNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x90.toByte(), 0x64), 0))
        assertNull(Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xEB.toByte(), 0x90.toByte(), 0x64), 0))
        assertNull(Mp3Header.parse(byteArrayOf(0xFE.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64), 0))
    }

    @Test
    fun confirmsOnlyConsecutiveFrames() {
        val frames = Mp3Fixtures.frames(count = 3)
        assertNotNull(Mp3Header.parseConfirmed(frames, 0))
        assertNull(Mp3Header.parseConfirmed(frames.copyOf(417 + 2), 0))
        val broken = frames.copyOf()
        broken[417] = 0
        assertNull(Mp3Header.parseConfirmed(broken, 0))
    }

    @Test
    fun parsesXingToc() {
        val frame = Mp3Fixtures.xingFrame(frames = 1000, bytes = 400_000)
        val header = assertNotNull(Mp3Header.parse(frame, 0))
        val xing = assertNotNull(XingInfo.parse(frame, 0, header))
        assertEquals(1000L, xing.frames)
        assertEquals(400_000L, xing.bytes)
        val toc = assertNotNull(xing.toc)
        assertEquals(100, toc.size)
        assertEquals(128, toc[50])
        val duration = assertNotNull(xing.durationMs(header))
        assertEquals(1000L * 1152 * 1000 / 44100, duration)
        assertEquals(0L, xing.seekOffset(0, duration, 400_000))
        val middle = assertNotNull(xing.seekOffset(duration / 2, duration, 400_000))
        assertTrue(middle in 198_000L..202_000L, "middle offset $middle")
        assertEquals(400_000L, xing.seekOffset(duration, duration, 400_000))
    }

    @Test
    fun parsesInfoTagWithoutToc() {
        val frame = Mp3Fixtures.xingFrame(frames = 50, bytes = null, tag = "Info")
        val header = assertNotNull(Mp3Header.parse(frame, 0))
        val xing = assertNotNull(XingInfo.parse(frame, 0, header))
        assertEquals(50L, xing.frames)
        assertNull(xing.bytes)
        assertNull(xing.seekOffset(1000, 2000, 1000))
    }

    @Test
    fun parsesVbriTable() {
        val frame = Mp3Fixtures.frames(count = 1)
        val vbri = byteArrayOf(
            'V'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte(), 'I'.code.toByte(),
            0, 1, 0, 0, 0, 50,
            0, 0, 0x0F, 0xA0.toByte(),
            0, 0, 0, 100,
            0, 4,
            0, 2,
            0, 1,
            0, 25,
            10, 20, 30, 40,
        )
        vbri.copyInto(frame, 36)
        val header = assertNotNull(Mp3Header.parse(frame, 0))
        val info = assertNotNull(VbriInfo.parse(frame, 0))
        assertEquals(4000L, info.bytes)
        assertEquals(100L, info.frames)
        assertEquals(listOf(20L, 40L, 60L, 80L), info.entries.toList())
        val entryMs = 25.0 * 1152 * 1000 / 44100
        assertEquals(0L, info.seekOffset(0, header))
        val second = assertNotNull(info.seekOffset(entryMs.toLong() + 1, header))
        assertTrue(second in 20L..23L, "second entry offset $second")
    }
}

object Mp3Fixtures {
    private val HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
    const val FRAME_LENGTH = 417

    fun frames(count: Int): ByteArray {
        val out = ByteArray(FRAME_LENGTH * count)
        for (i in 0 until count) HEADER.copyInto(out, i * FRAME_LENGTH)
        return out
    }

    fun xingFrame(frames: Int, bytes: Int?, tag: String = "Xing"): ByteArray {
        val frame = ByteArray(FRAME_LENGTH)
        HEADER.copyInto(frame, 0)
        var p = 36
        tag.forEach { frame[p++] = it.code.toByte() }
        val flags = 0x01 or (if (bytes != null) 0x02 else 0) or (if (bytes != null) 0x04 else 0)
        p = writeInt(frame, p, flags)
        p = writeInt(frame, p, frames)
        if (bytes != null) {
            p = writeInt(frame, p, bytes)
            for (i in 0 until 100) frame[p + i] = (i * 256 / 100).toByte()
        }
        return frame
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int): Int {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
        return offset + 4
    }
}
