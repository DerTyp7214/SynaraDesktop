package dev.dertyp.synara.player.audio.source

import dev.dertyp.PlatformUUID
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ISongService
import dev.dertyp.synara.testing.testSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SongAudioSourceTest {
    private val songService = mockk<ISongService>()
    private val scope = CoroutineScope(SupervisorJob())
    private val songId = randomPlatformUUID()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun songWithSize(fileSize: Long): UserSong = testSong(1, songId).copy(
        audio = AudioInfo(codec = "flac", sampleRate = 44100, bitsPerSample = 16, bitRate = 900_000, fileSize = fileSize, channels = 2)
    )

    private fun newSource(quality: Int, song: UserSong?, lookups: MutableList<PlatformUUID> = mutableListOf()) =
        SongAudioSource(songId, quality, songService) {
            lookups.add(it)
            song
        }

    private fun <T> bounded(block: () -> T): T = CompletableFuture.supplyAsync(block).get(10, TimeUnit.SECONDS)

    @Test
    fun prepareIsFalseWhenTheLookupFindsNothing() = runBlocking {
        val source = newSource(0, null)
        assertFalse(source.prepare())
        assertNull(source.song)
    }

    @Test
    fun hintsAreNullBeforePrepare() {
        val source = newSource(0, songWithSize(5000))
        assertNull(source.durationMsHint)
        assertNull(source.bitRateHint)
        assertNull(source.formatHint)
        assertNull(source.song)
    }

    @Test
    fun prepareLoadsTheSongAndExposesItsHints() = runBlocking {
        val song = songWithSize(5000)
        val lookups = mutableListOf<PlatformUUID>()
        val source = newSource(0, song, lookups)

        assertTrue(source.prepare())

        assertSame(song, source.song)
        assertEquals(listOf(songId), lookups)
        assertEquals(song.duration, source.durationMsHint)
        assertEquals(900_000L, source.bitRateHint)
        assertEquals("flac", source.formatHint)
    }

    @Test
    fun prepareDoesNotLookTheSongUpTwice() = runBlocking {
        val lookups = mutableListOf<PlatformUUID>()
        val source = newSource(0, songWithSize(5000), lookups)

        assertTrue(source.prepare())
        assertTrue(source.prepare())

        assertEquals(1, lookups.size)
    }

    @Test
    fun hintsWithoutAudioInfoAreNull() = runBlocking {
        val source = newSource(0, testSong(1, songId))
        assertTrue(source.prepare())
        assertEquals(240_000L, source.durationMsHint)
        assertNull(source.bitRateHint)
        assertNull(source.formatHint)
    }

    @Test
    fun cacheKeyCombinesSongIdAndQuality() {
        assertEquals("$songId:0", newSource(0, null).cacheKey)
        assertEquals("$songId:3", newSource(3, null).cacheKey)
    }

    @Test
    fun sizeAtQualityZeroUsesTheKnownFileSize() = runBlocking {
        val source = newSource(0, songWithSize(5000))
        source.prepare()

        assertEquals(5000L, source.size())

        coVerify(exactly = 0) { songService.getStreamSize(any()) }
    }

    @Test
    fun sizeAtQualityZeroAsksTheServerWithoutAFileSize() = runBlocking {
        coEvery { songService.getStreamSize(songId) } returns 777L
        val withZero = newSource(0, songWithSize(0))
        withZero.prepare()
        val unprepared = newSource(0, null)

        assertEquals(777L, withZero.size())
        assertEquals(777L, unprepared.size())
    }

    @Test
    fun sizeAtHigherQualityPrefersTheDownloadSize() = runBlocking {
        coEvery { songService.getDownloadSize(songId, 3, false) } returns 1234L
        val source = newSource(3, songWithSize(5000))
        source.prepare()

        assertEquals(1234L, source.size())

        coVerify(exactly = 0) { songService.getStreamSize(any()) }
    }

    @Test
    fun sizeAtHigherQualityFallsBackToTheFileSize() = runBlocking {
        for (downloadSize in listOf(0L, -1L)) {
            coEvery { songService.getDownloadSize(songId, 3, false) } returns downloadSize
            val source = newSource(3, songWithSize(5000))
            source.prepare()

            assertEquals(5000L, source.size(), "download size $downloadSize")
        }
        coVerify(exactly = 0) { songService.getStreamSize(any()) }
    }

    @Test
    fun sizeAtHigherQualityFallsBackToTheStreamSizeLast() = runBlocking {
        coEvery { songService.getDownloadSize(songId, 3, false) } returns 0L
        coEvery { songService.getStreamSize(songId) } returns 888L
        val source = newSource(3, songWithSize(0))
        source.prepare()

        assertEquals(888L, source.size())
    }

    @Test
    fun openAtQualityZeroStreamsFromTheOffset() = runBlocking {
        every { songService.streamSong(songId, 10L) } returns flowOf(byteArrayOf(1, 2, 3))
        val source = newSource(0, null)

        val stream = assertNotNull(source.open(10L, scope))

        assertEquals(listOf(1, 2, 3, -1), bounded { List(4) { stream.read() } })
        stream.close()
        verify(exactly = 0) { songService.downloadSong(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun openAtHigherQualityDownloadsFromTheOffsetWithoutForcing() = runBlocking {
        every { songService.downloadSong(songId, 3, 20L, force = false) } returns flowOf(byteArrayOf(9, 8))
        val source = newSource(3, null)

        val stream = assertNotNull(source.open(20L, scope))

        assertEquals(listOf(9, 8, -1), bounded { List(3) { stream.read() } })
        stream.close()
        verify(exactly = 0) { songService.streamSong(any(), any(), any()) }
    }

    @Test
    fun openGivesNullWhenTheServerHasNoStream() = runBlocking {
        every { songService.streamSong(songId, 0L) } returns null
        every { songService.downloadSong(songId, 3, 0L, force = false) } returns null

        assertNull(newSource(0, null).open(0L, scope))
        assertNull(newSource(3, null).open(0L, scope))
    }
}
