package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.Mp3Fixtures
import dev.dertyp.synara.player.audio.ProbeInfo
import dev.dertyp.synara.player.audio.decode.SeekFixtures.samples
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mp3SeekTest {
    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun open(
        source: SeekFixtures.BytesSource,
        info: ProbeInfo.Mp3,
        startMs: Long,
        scanBudget: Long = FrameSeek.SCAN_BUDGET,
    ): DecodedStream = runBlocking {
        assertNotNull(Mp3Decoding.open(logger, source, info, startMs, scope, scanBudget))
    }

    private fun assertSeeksMatchTheFullDecode(name: String, targets: List<Long>, withoutSeekTable: Boolean = false) {
        val data = SeekFixtures.bytes(name)
        val probed = assertIs<ProbeInfo.Mp3>(SeekFixtures.probe(SeekFixtures.BytesSource(data)))
        val info = if (withoutSeekTable) ProbeInfo.Mp3(probed.firstFrameOffset, probed.header, null, null) else probed
        val full = open(SeekFixtures.BytesSource(data), info, 0).samples()
        val source = SeekFixtures.BytesSource(data)
        for (target in targets) {
            val stream = open(source, info, target)
            assertEquals(target, stream.startMs, "$name at $target")
            val samples = stream.samples()
            val from = (target * stream.sampleRate / 1000 * stream.channels).toInt()
            assertEquals(full.size - from, samples.size, "$name at $target")
            assertContentEquals(full.copyOfRange(from, full.size), samples, "$name at $target")
        }
    }

    @Test
    fun mpeg1SeeksStartAtTheSameSampleAsTheFullDecode() {
        assertSeeksMatchTheFullDecode(SeekFixtures.MPEG1, listOf(13_000, 20_000, 30_000, 39_500))
    }

    @Test
    fun mpeg2SeeksStartAtTheSameSampleAsTheFullDecode() {
        assertSeeksMatchTheFullDecode(SeekFixtures.MPEG2, listOf(30_000, 60_000, 71_000, 2_500))
    }

    @Test
    fun seeksWithoutASeekTableAreExactToo() {
        assertSeeksMatchTheFullDecode(SeekFixtures.MPEG2, listOf(30_000, 60_000), withoutSeekTable = true)
    }

    @Test
    fun aLaterSeekOpensAtAScannedFrameInsteadOfTheAudioStart() {
        val data = SeekFixtures.bytes(SeekFixtures.MPEG1)
        val source = SeekFixtures.BytesSource(data)
        val info = assertIs<ProbeInfo.Mp3>(SeekFixtures.probe(source))
        open(source, info, 30_000)
        source.opens.clear()
        open(source, info, 20_000)
        assertEquals(1, source.opens.size)
        assertTrue(source.opens.single() > info.audioStart)
    }

    @Test
    fun aSeekBeyondTheScanBudgetLandsExactlyOnConstantBitRate() {
        val frames = constantBitRateFrames(count = 2000)
        val source = SeekFixtures.BytesSource(frames)
        val info = ProbeInfo.Mp3(0, assertNotNull(Mp3Header.parseConfirmed(frames, 0)), null, null)
        val stream = open(source, info, 30_000, scanBudget = 0)
        assertEquals(30_000, stream.startMs)
        assertTrue(source.opens.none { it == 0L })
        assertEquals((2000 * 1152 - 30L * 44100) * 2, stream.samples().size.toLong())
    }

    @Test
    fun scanningSkipsJunkBetweenFrames() {
        val frames = Mp3Fixtures.frames(count = 500) + ByteArray(1000) { 0x11 } + Mp3Fixtures.frames(count = 1500)
        val source = SeekFixtures.BytesSource(frames)
        val info = ProbeInfo.Mp3(0, assertNotNull(Mp3Header.parseConfirmed(frames, 0)), null, null)
        val stream = open(source, info, 30_000)
        assertEquals(30_000, stream.startMs)
        assertEquals((2000 * 1152 - 30L * 44100) * 2, stream.samples().size.toLong())
    }

    @Test
    fun aSeekPastTheEndReportsTheEnd() {
        val frames = Mp3Fixtures.frames(count = 100)
        val source = SeekFixtures.BytesSource(frames)
        val info = ProbeInfo.Mp3(0, assertNotNull(Mp3Header.parseConfirmed(frames, 0)), null, null)
        val start = assertNotNull(runBlocking {
            FrameSeek.start(source, layoutOf(info), 0, frames.size.toLong(), 60_000, scope, locate = { 0 }, landedFrame = { _, f -> f })
        })
        start.input.close()
        assertEquals(100L * 1152 * 1000 / 44100, start.startMs)
    }

    private fun constantBitRateFrames(count: Int): ByteArray {
        val frameBytes = 1152.0 / 8 * 128_000 / 44_100
        val out = ByteArray((count * frameBytes).toInt())
        for (i in 0 until count) {
            val offset = (i * frameBytes).toInt()
            val padded = ((i + 1) * frameBytes).toInt() - offset > Mp3Fixtures.FRAME_LENGTH
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), if (padded) 0x92.toByte() else 0x90.toByte(), 0x64).copyInto(out, offset)
        }
        return out
    }

    private fun layoutOf(info: ProbeInfo.Mp3) = object : FrameLayout {
        override val headerLength = 4
        override val samplesPerFrame = info.header.samplesPerFrame
        override val sampleRate = info.header.sampleRate
        override fun frameLength(bytes: ByteArray, offset: Int): Int? = Mp3Header.parse(bytes, offset)?.frameLength
        override fun confirm(bytes: ByteArray, offset: Int, length: Int): Boolean =
            Mp3Header.parseConfirmed(bytes, offset, length) != null
    }
}
