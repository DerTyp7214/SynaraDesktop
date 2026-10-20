package dev.dertyp.synara.services

import dev.dertyp.data.Artist
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.synara.scrobble.MusicBrainzService
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
import dev.dertyp.synara.testing.testSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShareLinkBuilderTest {
    private val musicBrainzService = mockk<IMusicBrainzService>()
    private val directMusicBrainzService = mockk<MusicBrainzService>()
    private val logger = mockk<Logger>(relaxed = true)
    private val recording = MusicBrainzRecording(id = randomPlatformUUID(), title = "Recording")
    private val release = MusicBrainzRelease(id = randomPlatformUUID(), title = "Release")

    private lateinit var builder: ShareLinkBuilder

    @BeforeTest
    fun setUp() {
        coEvery { directMusicBrainzService.searchMb(any()) } returns null
        coEvery { directMusicBrainzService.searchAlbumMb(any()) } returns null
        builder = ShareLinkBuilder(musicBrainzService, directMusicBrainzService, logger)
    }

    @Test
    fun songLinkWithoutMusicBrainzMatch() = runTest {
        val song = testSong(1, duration = 180_000).copy(title = "Test Title", artists = listOf(testArtist(1)))

        assertEquals(
            "https://synara.audio/share?type=song&title=Test%20Title&duration=180000&artist=Artist%201",
            builder.songLink(song)
        )
    }

    @Test
    fun songLinkAppendsIsrcArtistsAndSearchedMbid() = runTest {
        val song = testSong(1, duration = 1000)
            .copy(title = "Song (Remastered 2011)", artists = listOf(testArtist(1), testArtist(2)), isrc = "USABC1234567")
        coEvery { directMusicBrainzService.searchMb(song) } returns recording

        assertEquals(
            "https://synara.audio/share?type=song&title=Song&duration=1000&artist=Artist%201&artist=Artist%202" +
                "&isrc=USABC1234567&mbid=${recording.id}",
            builder.songLink(song)
        )
    }

    @Test
    fun songLinkUsesStoredMusicBrainzIdBeforeSearching() = runTest {
        val id = randomPlatformUUID()
        val song = testSong(1).copy(title = "T", musicBrainzId = id)
        coEvery { musicBrainzService.getRecording(id) } returns recording

        assertEquals(
            "https://synara.audio/share?type=song&title=T&duration=240000&mbid=${recording.id}",
            builder.songLink(song)
        )
        coVerify(exactly = 0) { directMusicBrainzService.searchMb(any()) }
    }

    @Test
    fun songLinkFallsBackToSearchWhenStoredIdResolvesNothing() = runTest {
        val id = randomPlatformUUID()
        val song = testSong(1).copy(title = "T", musicBrainzId = id)
        coEvery { musicBrainzService.getRecording(id) } returns null
        coEvery { directMusicBrainzService.searchMb(song) } returns recording

        assertEquals(
            "https://synara.audio/share?type=song&title=T&duration=240000&mbid=${recording.id}",
            builder.songLink(song)
        )
    }

    @Test
    fun songLinkSurvivesLookupFailure() = runTest {
        val id = randomPlatformUUID()
        val song = testSong(1).copy(title = "T", musicBrainzId = id)
        coEvery { musicBrainzService.getRecording(id) } throws IllegalStateException("offline")

        assertEquals("https://synara.audio/share?type=song&title=T&duration=240000", builder.songLink(song))
    }

    @Test
    fun albumLinkWithBarcodeAndSearchedMbid() = runTest {
        val album = testAlbum(1).copy(name = "Test Album", artists = listOf(testArtist(1)), barcode = "0123456789012")
        coEvery { directMusicBrainzService.searchAlbumMb(album) } returns release

        assertEquals(
            "https://synara.audio/share?type=album&title=Test%20Album&artist=Artist%201" +
                "&barcode=0123456789012&mbid=${release.id}",
            builder.albumLink(album)
        )
    }

    @Test
    fun albumLinkUsesStoredMusicBrainzIdBeforeSearching() = runTest {
        val id = randomPlatformUUID()
        val album = testAlbum(1).copy(name = "A", musicBrainzId = id)
        coEvery { musicBrainzService.getRelease(id) } returns release

        assertEquals("https://synara.audio/share?type=album&title=A&mbid=${release.id}", builder.albumLink(album))
        coVerify(exactly = 0) { directMusicBrainzService.searchAlbumMb(any()) }
    }

    @Test
    fun albumLinkWithoutMatchHasNoMbid() = runTest {
        val album = testAlbum(1).copy(name = "Test Album")

        assertEquals("https://synara.audio/share?type=album&title=Test%20Album", builder.albumLink(album))
    }

    @Test
    fun artistLinkCarriesMusicBrainzId() {
        val id = randomPlatformUUID()
        val artist = Artist(id = randomPlatformUUID(), name = "Some Artist", isGroup = false, musicBrainzId = id)

        assertEquals("https://synara.audio/share?type=artist&title=Some%20Artist&mbid=$id", builder.artistLink(artist))
    }

    @Test
    fun artistLinkWithoutMusicBrainzIdOmitsMbid() {
        val artist = Artist(id = randomPlatformUUID(), name = "Some Artist", isGroup = false)

        assertEquals("https://synara.audio/share?type=artist&title=Some%20Artist", builder.artistLink(artist))
    }
}
