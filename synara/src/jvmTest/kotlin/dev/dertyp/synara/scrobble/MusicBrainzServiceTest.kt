package dev.dertyp.synara.scrobble

import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
import dev.dertyp.synara.testing.testSong
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MusicBrainzServiceTest {
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val remote = mockk<IMusicBrainzService>(relaxed = true)
    private val recording = MusicBrainzRecording(id = randomPlatformUUID(), title = "Recording")
    private val release = MusicBrainzRelease(id = randomPlatformUUID(), title = "Release")

    private lateinit var service: MusicBrainzService

    @BeforeTest
    fun setUp() {
        every { rpcServiceManager.connectionState } returns
            MutableStateFlow(RpcServiceManager.ConnectionState.Authenticated)
        coEvery { remote.getRecording(any()) } returns recording
        coEvery { remote.searchRecording(any(), any()) } returns recording
        coEvery { remote.searchRelease(any(), any()) } returns release
        service = MusicBrainzService(AppJson, mockk<Logger>(relaxed = true), rpcServiceManager, remote)
    }

    @Test
    fun recordingComesFromTheServerWithOneCall() = runTest {
        val id = randomPlatformUUID()

        assertEquals(recording, service.getRecording(id))
        coVerify(exactly = 1) { remote.getRecording(id) }
        coVerify(exactly = 1) { remote.getRecording(any()) }
    }

    @Test
    fun recordingUnknownToTheServerIsNull() = runTest {
        val id = randomPlatformUUID()
        coEvery { remote.getRecording(any()) } returns null

        assertNull(service.getRecording(id))
        coVerify(exactly = 1) { remote.getRecording(id) }
    }

    @Test
    fun songSearchSendsTheCleanedTitleAndTheArtistNames() = runTest {
        val artists = listOf(testArtist(0), testArtist(1))
        val song = testSong(0, artists = artists).copy(title = "Song Title [Explicit]")
        val names = artists.map { it.name }

        assertEquals(recording, service.searchMb(song))
        coVerify(exactly = 1) {
            remote.searchRecording(title = "Song Title", artists = names)
        }
    }

    @Test
    fun albumSearchSendsTheCleanedTitleAndTheArtistNames() = runTest {
        val artists = listOf(testArtist(0), testArtist(1))
        val album = testAlbum(0).copy(name = "Album Name [Explicit]", artists = artists)
        val names = artists.map { it.name }

        assertEquals(release, service.searchAlbumMb(album))
        coVerify(exactly = 1) {
            remote.searchRelease(title = "Album Name", artists = names)
        }
    }

    @Test
    fun uuidShapedQueryFoundOnTheServerGivesThatSingleRecording() = runTest {
        val id = randomPlatformUUID()

        assertEquals(listOf(recording), service.searchRecordings(id.toString()))
        coVerify(exactly = 1) { remote.getRecording(id) }
    }

    @Test
    fun aCancelledRecordingLookupOnTheServerIsNotAnsweredFromMusicBrainz() = runTest {
        coEvery { remote.getRecording(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.getRecording(randomPlatformUUID()) }
    }

    @Test
    fun aCancelledSongSearchOnTheServerIsNotAnsweredFromMusicBrainz() = runTest {
        coEvery { remote.searchRecording(any(), any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.searchMb(testSong(0)) }
    }
}
