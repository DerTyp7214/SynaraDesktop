package dev.dertyp.synara.player.audio.decode

import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.DecodedStream
import dev.dertyp.synara.player.audio.FlacFixtures
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FlacSeekTest {
    private class BytesSource(private val bytes: ByteArray) : AudioSource {
        val opens = CopyOnWriteArrayList<Long>()
        override val cacheKey: String = "flac-seek"
        override val durationMsHint: Long? = null
        override val bitRateHint: Long? = null
        override val formatHint: String? = null

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
            opens.add(offset)
            return if (offset > bytes.size) null else ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    private class Fixture(val bytes: ByteArray, val samples: ShortArray) {
        val metadata: ByteArray = bytes.copyOf(metadataLength(bytes))
    }

    private val logger = mockk<Logger>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun open(fixture: Fixture, positionMs: Long, source: BytesSource = BytesSource(fixture.bytes)): DecodedStream = runBlocking {
        assertNotNull(
            FlacOpusDecoding.open(
                logger, source, fixture.metadata, isOgg = false, positionMs = positionMs,
                totalDuration = fixture.samples.size * 1000L / 44100, scope = scope
            )
        )
    }

    private fun DecodedStream.pcm(): ShortArray = runBlocking {
        withTimeout(30.seconds) {
            val out = mutableListOf<Short>()
            pcmFlow.collect { buffer -> while (buffer.hasRemaining()) out += buffer.get() }
            out.toShortArray()
        }
    }

    private fun assertSeeksExactly(fixture: Fixture, positionMs: Long, maxOpens: Int) {
        val source = BytesSource(fixture.bytes)
        val stream = open(fixture, positionMs, source)
        val pcm = stream.pcm()
        val target = (positionMs * 44100 / 1000).toInt()

        assertEquals(positionMs, stream.startMs)
        assertEquals(fixture.samples.size - target, pcm.size)
        assertContentEquals(fixture.samples.copyOfRange(target, fixture.samples.size), pcm)
        assertTrue(source.opens.size <= maxOpens, "opened ${source.opens}")
    }

    @Test
    fun theFullDecodeMatchesTheFixture() {
        assertContentEquals(silenceThenNoise.samples, open(silenceThenNoise, 0).pcm())
    }

    @Test
    fun seeksLandOnTheTargetSampleDespiteUnevenFrameSizes() {
        for (positionMs in listOf(30_000L, 45_500L, 60_000L, 79_000L)) {
            assertSeeksExactly(silenceThenNoise, positionMs, maxOpens = 6)
        }
    }

    @Test
    fun seeksIntoTheSilenceLandOnTheTargetSample() {
        assertSeeksExactly(silenceThenNoise, 12_345L, maxOpens = 6)
    }

    @Test
    fun seeksUseTheSeekTable() {
        for (positionMs in listOf(30_000L, 60_000L, 79_000L)) {
            assertSeeksExactly(withSeekTable, positionMs, maxOpens = 2)
        }
    }

    @Test
    fun seeksInAStreamWithVariableBlockSizesUseTheSampleNumbers() {
        for (positionMs in listOf(15_000L, 30_000L)) {
            assertSeeksExactly(variable, positionMs, maxOpens = 6)
        }
    }

    @Test
    fun framesHeadersHiddenInTheAudioAreNotMistakenForFrames() {
        for (positionMs in listOf(30_000L, 60_000L)) {
            assertSeeksExactly(withFalseSyncs, positionMs, maxOpens = 6)
        }
    }

    @Test
    fun aSourceThatCannotBeReadAtAnOffsetDecodesFromTheStart() {
        val source = object : AudioSource {
            override val cacheKey = "unranged"
            override val durationMsHint: Long? = null
            override val bitRateHint: Long? = null
            override val formatHint: String? = null
            override suspend fun size(): Long = silenceThenNoise.bytes.size.toLong()
            override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? =
                if (offset > 0) null else ByteArrayInputStream(silenceThenNoise.bytes)
        }
        val stream = runBlocking {
            assertNotNull(
                FlacOpusDecoding.open(logger, source, silenceThenNoise.metadata, isOgg = false, 30_000, 80_000, scope)
            )
        }
        val target = 30 * 44100

        assertEquals(30_000L, stream.startMs)
        assertContentEquals(silenceThenNoise.samples.copyOfRange(target, silenceThenNoise.samples.size), stream.pcm())
    }

    @Test
    fun aFalseSyncWithAValidHeaderIsSkippedForTheNextRealFrame() {
        val info = assertNotNull(FlacStreamInfo.parse(silenceThenNoise.metadata))
        val fake = FlacFixtures.frameHeader(channels = 1, count = 4096, number = 3) + byteArrayOf(0x02, 0x12, 0x34)
        val real = silenceThenNoise.bytes.copyOfRange(silenceThenNoise.metadata.size, silenceThenNoise.metadata.size + 40_000)
        val noise = Random(7).nextBytes(500).map { if (it == 0xFF.toByte()) 0 else it }.toByteArray()
        val buffer = noise + fake + noise + real

        assertNotNull(FlacFrameHeader.parse(buffer, noise.size, buffer.size, info))
        val found = FlacSeek.scanner(info).scan(buffer, buffer.size, eof = false, from = 0)

        found as SeekSearch.Scan.Found
        assertEquals(noise.size * 2 + fake.size, found.index)
        assertEquals(0L, found.position)
    }

    @Test
    fun aHeaderWithABrokenChecksumIsNotAFrame() {
        val info = assertNotNull(FlacStreamInfo.parse(silenceThenNoise.metadata))
        val header = FlacFixtures.frameHeader(channels = 1, count = 4096, number = 3) + byteArrayOf(0x02, 0, 0)
        header[header.size - 4] = (header[header.size - 4] + 1).toByte()

        assertNull(FlacFrameHeader.parse(header, 0, header.size, info))
    }

    @Test
    fun aHeaderThatDoesNotMatchTheStreamInfoIsNotAFrame() {
        val info = assertNotNull(FlacStreamInfo.parse(silenceThenNoise.metadata))
        val stereo = FlacFixtures.frameHeader(channels = 2, count = 4096, number = 3) + byteArrayOf(0x02, 0, 0)

        assertNull(FlacFrameHeader.parse(stereo, 0, stereo.size, info))
    }

    private companion object {
        const val BLOCK = 4096

        fun metadataLength(bytes: ByteArray): Int {
            var position = 4
            while (true) {
                val last = bytes[position].toInt() and 0x80 != 0
                val length = ((bytes[position + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[position + 2].toInt() and 0xFF) shl 8) or (bytes[position + 3].toInt() and 0xFF)
                position += 4 + length
                if (last) return position
            }
        }

        fun silenceThenNoise(silenceSeconds: Int, noiseSeconds: Int, seed: Int): ShortArray {
            val random = Random(seed)
            val silence = silenceSeconds * 44100
            return ShortArray((silenceSeconds + noiseSeconds) * 44100 + 1234) {
                if (it < silence) 0 else (random.nextInt(-12000, 12000)).toShort()
            }
        }

        fun hideFrameHeaders(samples: ShortArray) {
            var frame = 0
            while ((frame + 1) * BLOCK <= samples.size) {
                if (samples[frame * BLOCK + 100] != 0.toShort()) {
                    val number = (frame - 3).coerceAtLeast(0)
                    val fake = FlacFixtures.frameHeader(channels = 1, count = BLOCK, number = number) + byteArrayOf(0x02)
                    val padded = if (fake.size % 2 == 0) fake else fake + byteArrayOf(0x40)
                    for (i in padded.indices step 2) {
                        samples[frame * BLOCK + 100 + i / 2] = (((padded[i].toInt() and 0xFF) shl 8) or (padded[i + 1].toInt() and 0xFF)).toShort()
                    }
                }
                frame++
            }
        }

        val silenceThenNoise: Fixture by lazy {
            val samples = silenceThenNoise(20, 60, seed = 1)
            Fixture(FlacFixtures.stream44100Bit16(listOf(samples), BLOCK), samples)
        }

        val withSeekTable: Fixture by lazy {
            val samples = silenceThenNoise(20, 60, seed = 2)
            Fixture(FlacFixtures.stream44100Bit16(listOf(samples), BLOCK, seekPointEvery = 100), samples)
        }

        val variable: Fixture by lazy {
            val samples = silenceThenNoise(15, 25, seed = 3)
            Fixture(FlacFixtures.stream44100Bit16(listOf(samples), BLOCK, variable = true), samples)
        }

        val withFalseSyncs: Fixture by lazy {
            val samples = silenceThenNoise(20, 60, seed = 4)
            hideFrameHeaders(samples)
            Fixture(FlacFixtures.stream44100Bit16(listOf(samples), BLOCK), samples)
        }
    }
}
