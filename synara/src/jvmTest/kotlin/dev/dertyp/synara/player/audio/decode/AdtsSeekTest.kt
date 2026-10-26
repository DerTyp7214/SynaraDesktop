package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.DecodedStream
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

class AdtsSeekTest {
    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun open(source: SeekFixtures.BytesSource, info: ProbeInfo.Adts, startMs: Long): DecodedStream = runBlocking {
        assertNotNull(AdtsDecoding.open(logger, source, info, startMs, scope))
    }

    @Test
    fun seeksStartAtTheSameSampleAsTheFullDecode() {
        val data = SeekFixtures.bytes(SeekFixtures.ADTS)
        val source = SeekFixtures.BytesSource(data)
        val info = assertIs<ProbeInfo.Adts>(SeekFixtures.probe(source))
        val full = open(SeekFixtures.BytesSource(data), info, 0).samples()
        for (target in listOf(30_000L, 60_000L, 71_000L, 3_000L)) {
            val stream = open(source, info, target)
            assertEquals(target, stream.startMs, "at $target")
            val samples = stream.samples()
            val from = (target * stream.sampleRate / 1000 * stream.channels).toInt()
            assertEquals(full.size - from, samples.size, "at $target")
            assertContentEquals(full.copyOfRange(from, full.size), samples, "at $target")
        }
    }

    @Test
    fun theAverageFrameSizeOfTheProbeWouldMissTheTarget() {
        val data = SeekFixtures.bytes(SeekFixtures.ADTS)
        val info = assertIs<ProbeInfo.Adts>(SeekFixtures.probe(SeekFixtures.BytesSource(data)))
        val estimatedMs = (data.size - info.audioStart) * 8 * 1000 / AdtsDecoding.bitRate(info)
        assertTrue(estimatedMs > 72_000 * 2, "estimated duration $estimatedMs")
    }
}
