package dev.dertyp.synara.viewmodels

import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.data.RadioChannelSearchResults
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.IRadioChannelService
import dev.dertyp.services.IRadioService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testSongs
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioScreenModelTest {

    private val channelId = randomPlatformUUID()
    private val songs = testSongs(2)
    private val albums = listOf(testAlbum(1))
    private val artists = listOf(mockk<Artist>())

    private val radioService = mockk<IRadioService>()
    private val radioChannelService = mockk<IRadioChannelService> {
        coEvery { listChannels() } returns emptyList()
    }
    private val songService = mockk<ISongService> {
        coEvery { rankedSearch(any(), any(), any(), any(), any()) } returns PaginatedResponse(data = songs)
    }
    private val albumService = mockk<IAlbumService> {
        coEvery { rankedSearch(any(), any(), any()) } returns PaginatedResponse(data = albums)
    }
    private val artistService = mockk<IArtistService> {
        coEvery { rankedSearch(any(), any(), any()) } returns PaginatedResponse(data = artists)
    }
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val playerModel = mockk<PlayerModel>(relaxed = true)

    private fun TestScope.createModel(): RadioScreenModel {
        val model = RadioScreenModel(
            radioService,
            radioChannelService,
            songService,
            albumService,
            artistService,
            rpcServiceManager,
            playerModel,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return model
    }

    @Test
    fun channelsAreSortedByPosition() = runTest {
        val late = RadioChannel(randomPlatformUUID(), "Late", position = 2)
        val first = RadioChannel(randomPlatformUUID(), "First", position = 0)
        val middle = RadioChannel(randomPlatformUUID(), "Middle", position = 1)
        coEvery { radioChannelService.listChannels() } returns listOf(late, first, middle)

        val model = createModel()

        assertEquals(listOf(first, middle, late), model.state.value.channels)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun aFailingChannelListSetsTheErrorAndStopsLoading() = runTest {
        coEvery { radioChannelService.listChannels() } throws IOException("down")

        val model = createModel()

        assertNotNull(model.state.value.error)
        assertEquals(false, model.state.value.isLoading)
    }

    @Test
    fun aLibrarySearchUnderTwoCharactersClearsTheResultsWithoutACall() = runTest {
        val model = createModel()
        model.searchLibrary("ab")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(songs, model.state.value.librarySongs)

        model.searchLibrary("a")

        assertTrue(model.state.value.librarySongs.isEmpty())
        assertTrue(model.state.value.libraryAlbums.isEmpty())
        assertTrue(model.state.value.libraryArtists.isEmpty())
        advanceTimeBy(301)
        runCurrent()
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun theLibrarySearchDebouncesAndOnlyTheLatestQueryIsSent() = runTest {
        val model = createModel()

        model.searchLibrary("ab")
        advanceTimeBy(100)
        runCurrent()
        model.searchLibrary("abc")
        advanceTimeBy(299)
        runCurrent()
        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }

        advanceTimeBy(2)
        runCurrent()

        coVerify(exactly = 1) { songService.rankedSearch(0, 10, "abc", true, false) }
        coVerify(exactly = 1) { albumService.rankedSearch(0, 10, "abc") }
        coVerify(exactly = 1) { artistService.rankedSearch(0, 10, "abc") }
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(songs, model.state.value.librarySongs)
        assertEquals(albums, model.state.value.libraryAlbums)
        assertEquals(artists, model.state.value.libraryArtists)
    }

    @Test
    fun aDebouncedChannelContentRequestCancelsThePreviousOne() = runTest {
        val results = mockk<RadioChannelSearchResults>()
        coEvery { radioChannelService.rankedSearch(any(), any(), any(), any(), any()) } returns results
        val model = createModel()

        model.loadChannelContent(channelId, "a", debounce = true)
        advanceTimeBy(100)
        runCurrent()
        model.loadChannelContent(channelId, "ab", debounce = true)
        advanceTimeBy(301)
        runCurrent()

        coVerify(exactly = 1) { radioChannelService.rankedSearch(channelId, "ab", true, 0, 50) }
        coVerify(exactly = 1) { radioChannelService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(results, model.state.value.channelContent)
        assertEquals(false, model.state.value.channelContentLoading)
    }

    @Test
    fun aChannelContentRequestInFlightIsReplacedByTheNextOne() = runTest {
        val stale = mockk<RadioChannelSearchResults>()
        val fresh = mockk<RadioChannelSearchResults>()
        val gate = CompletableDeferred<RadioChannelSearchResults>()
        coEvery { radioChannelService.rankedSearch(channelId, "a", any(), any(), any()) } coAnswers { gate.await() }
        coEvery { radioChannelService.rankedSearch(channelId, "b", any(), any(), any()) } returns fresh
        val model = createModel()

        model.loadChannelContent(channelId, "a")
        runCurrent()
        model.loadChannelContent(channelId, "b")
        runCurrent()
        gate.complete(stale)
        runCurrent()

        assertEquals(fresh, model.state.value.channelContent)
        assertEquals(false, model.state.value.channelContentLoading)
    }

    @Test
    fun supersedingAChannelContentRequestInFlightLeavesNoError() = runTest {
        val gate = CompletableDeferred<RadioChannelSearchResults>()
        coEvery { radioChannelService.rankedSearch(channelId, "a", any(), any(), any()) } coAnswers { gate.await() }
        coEvery { radioChannelService.rankedSearch(channelId, "b", any(), any(), any()) } returns mockk()
        val model = createModel()

        model.loadChannelContent(channelId, "a")
        runCurrent()
        model.loadChannelContent(channelId, "b")
        runCurrent()

        assertNull(model.state.value.error)
    }

    @Test
    fun supersedingALibrarySearchInFlightLeavesNoError() = runTest {
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.rankedSearch(0, 10, "ab", true, false) } coAnswers { gate.await() }
        val model = createModel()

        model.searchLibrary("ab")
        advanceTimeBy(301)
        runCurrent()
        model.searchLibrary("abc")
        advanceTimeBy(301)
        runCurrent()

        assertEquals(songs, model.state.value.librarySongs)
        assertNull(model.state.value.error)
    }

    private fun TestScope.assertAFailedLibrarySearchSetsTheError() {
        val model = createModel()

        model.searchLibrary("ab")
        advanceTimeBy(301)
        runCurrent()

        val state = model.state.value
        assertNotNull(state.error)
        assertTrue(state.librarySongs.isEmpty() || state.librarySongs == songs)
        assertTrue(state.libraryAlbums.isEmpty() || state.libraryAlbums == albums)
        assertTrue(state.libraryArtists.isEmpty() || state.libraryArtists == artists)
    }

    @Test
    fun aFailingSongSearchSetsTheError() = runTest {
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } throws IOException("down")

        assertAFailedLibrarySearchSetsTheError()
    }

    @Test
    fun aFailingAlbumSearchSetsTheError() = runTest {
        coEvery { albumService.rankedSearch(any(), any(), any()) } throws IOException("down")

        assertAFailedLibrarySearchSetsTheError()
    }

    @Test
    fun aFailingArtistSearchSetsTheError() = runTest {
        coEvery { artistService.rankedSearch(any(), any(), any()) } throws IOException("down")

        assertAFailedLibrarySearchSetsTheError()
    }

    @Test
    fun aLibrarySearchAfterAFailedOneDeliversItsResults() = runTest {
        coEvery { songService.rankedSearch(0, 10, "ab", true, false) } throws IOException("down")
        val model = createModel()
        model.searchLibrary("ab")
        advanceTimeBy(301)
        runCurrent()

        model.searchLibrary("abc")
        advanceTimeBy(301)
        runCurrent()

        assertEquals(songs, model.state.value.librarySongs)
        assertEquals(albums, model.state.value.libraryAlbums)
        assertEquals(artists, model.state.value.libraryArtists)
    }

    @Test
    fun clearingTheChannelContentCancelsAContentRequestInFlight() = runTest {
        val gate = CompletableDeferred<RadioChannelSearchResults>()
        coEvery { radioChannelService.rankedSearch(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val model = createModel()
        model.loadChannelContent(channelId, "a")
        runCurrent()
        coVerify(exactly = 1) { radioChannelService.rankedSearch(channelId, "a", true, 0, 50) }

        model.clearChannelContent()
        gate.complete(mockk())
        runCurrent()

        assertNull(model.state.value.channelContent)
        assertNull(model.state.value.error)
    }

    @Test
    fun clearingTheChannelContentCancelsALibrarySearchInFlightAndEmptiesItsResults() = runTest {
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val model = createModel()
        model.searchLibrary("ab")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(albums, model.state.value.libraryAlbums)

        model.clearChannelContent()
        gate.complete(PaginatedResponse(data = songs))
        runCurrent()

        assertTrue(model.state.value.librarySongs.isEmpty())
        assertTrue(model.state.value.libraryAlbums.isEmpty())
        assertTrue(model.state.value.libraryArtists.isEmpty())
        assertNull(model.state.value.error)
    }

    private fun stubTwoChannels(): List<RadioChannel> {
        val channels = listOf(
            RadioChannel(randomPlatformUUID(), "First", position = 0),
            RadioChannel(randomPlatformUUID(), "Second", position = 1)
        )
        coEvery { radioChannelService.listChannels() } returns channels
        return channels
    }

    @Test
    fun aFailedChannelDeletionSetsTheErrorAndKeepsTheChannels() = runTest {
        val channels = stubTwoChannels()
        coEvery { radioChannelService.deleteChannel(any()) } throws IOException("down")
        val model = createModel()

        model.deleteChannel(channels[0].id)
        runCurrent()

        coVerify(exactly = 1) { radioChannelService.deleteChannel(channels[0].id) }
        assertNotNull(model.state.value.error)
        assertEquals(channels, model.state.value.channels)
        assertEquals(false, model.state.value.isLoading)
    }

    @Test
    fun aFailedChannelUpdateReturnsFalseSetsTheErrorAndKeepsTheChannels() = runTest {
        val channels = stubTwoChannels()
        coEvery { radioChannelService.updateChannel(any(), any()) } throws IOException("down")
        val model = createModel()
        val change = InsertableRadioChannel(name = "Renamed")

        val updated = model.updateChannel(channels[0].id, change)
        runCurrent()

        assertEquals(false, updated)
        coVerify(exactly = 1) { radioChannelService.updateChannel(channels[0].id, change) }
        assertNotNull(model.state.value.error)
        assertEquals(channels, model.state.value.channels)
    }

    @Test
    fun aFailedChannelCreationReturnsNullAndSetsTheError() = runTest {
        coEvery { radioChannelService.createChannel(any()) } throws IllegalStateException("boom")
        val model = createModel()

        val id = model.createChannel(InsertableRadioChannel(name = "New"))
        runCurrent()

        assertNull(id)
        assertNotNull(model.state.value.error)
        coVerify(exactly = 1) { radioChannelService.listChannels() }
    }

    @Test
    fun aCreatedChannelReturnsItsIdAndReloadsTheChannels() = runTest {
        val created = randomPlatformUUID()
        coEvery { radioChannelService.createChannel(any()) } returns created
        val model = createModel()

        val id = model.createChannel(InsertableRadioChannel(name = "New"))
        runCurrent()

        assertEquals(created, id)
        coVerify(exactly = 2) { radioChannelService.listChannels() }
    }

    @Test
    fun addingAChannelItemReloadsTheContentAndChannelsEvenWhenItFails() = runTest {
        val itemId = randomPlatformUUID()
        coEvery { radioChannelService.addChannelItem(any(), any(), any()) } throws IOException("down")
        coEvery { radioChannelService.rankedSearch(any(), any(), any(), any(), any()) } returns mockk()
        val model = createModel()

        model.addChannelItem(channelId, RadioChannelItemType.SONG, itemId, "query")
        runCurrent()

        coVerify(exactly = 1) { radioChannelService.addChannelItem(channelId, RadioChannelItemType.SONG, itemId) }
        coVerify(exactly = 1) { radioChannelService.rankedSearch(channelId, "query", true, 0, 50) }
        coVerify(exactly = 2) { radioChannelService.listChannels() }
    }

    @Test
    fun removingAChannelItemReloadsTheContentAndChannelsEvenWhenItFails() = runTest {
        val itemId = randomPlatformUUID()
        coEvery { radioChannelService.removeChannelItem(any(), any(), any()) } throws IOException("down")
        coEvery { radioChannelService.rankedSearch(any(), any(), any(), any(), any()) } returns mockk()
        val model = createModel()

        model.removeChannelItem(channelId, RadioChannelItemType.SONG, itemId, "query")
        runCurrent()

        coVerify(exactly = 1) { radioChannelService.removeChannelItem(channelId, RadioChannelItemType.SONG, itemId) }
        coVerify(exactly = 1) { radioChannelService.rankedSearch(channelId, "query", true, 0, 50) }
        coVerify(exactly = 2) { radioChannelService.listChannels() }
    }
}
