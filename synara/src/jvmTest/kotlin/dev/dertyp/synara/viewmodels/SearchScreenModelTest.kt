package dev.dertyp.synara.viewmodels

import dev.dertyp.data.Artist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserPlaylist
import dev.dertyp.logging.Logger
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testSongs
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchScreenModelTest {

    private val songs = testSongs(2)
    private val lyricSongs = testSongs(3)
    private val albums = listOf(testAlbum(1))
    private val artists = listOf(mockk<Artist>())
    private val playlists = listOf(mockk<UserPlaylist>())

    private val songService = mockk<ISongService> {
        coEvery { rankedSearch(any(), any(), any(), any(), any()) } returns PaginatedResponse(data = songs)
        coEvery { searchByLyrics(any(), any(), any(), any()) } returns PaginatedResponse(data = lyricSongs)
    }
    private val albumService = mockk<IAlbumService> {
        coEvery { rankedSearch(any(), any(), any()) } returns PaginatedResponse(data = albums)
    }
    private val artistService = mockk<IArtistService> {
        coEvery { rankedSearch(any(), any(), any()) } returns PaginatedResponse(data = artists)
    }
    private val userPlaylistService = mockk<IUserPlaylistService> {
        coEvery { rankedSearch(any(), any(), any(), any()) } returns PaginatedResponse(data = playlists)
    }
    private val globalState = mockk<GlobalStateModel> {
        every { user } returns MutableStateFlow(null)
    }

    private fun TestScope.createModel() = SearchScreenModel(
        songService,
        albumService,
        artistService,
        userPlaylistService,
        globalState,
        mockk<Logger>(relaxed = true),
        TestDispatchers(StandardTestDispatcher(testScheduler))
    )

    private fun TestScope.passDebounce() {
        advanceTimeBy(401)
        runCurrent()
    }

    @Test
    fun twoQueriesWithinTheDebounceHitTheServicesOnceWithTheSecond() = runTest {
        val model = createModel()

        model.search("fi")
        advanceTimeBy(200)
        runCurrent()
        model.search("fin")
        advanceTimeBy(200)
        runCurrent()
        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }

        passDebounce()

        coVerify(exactly = 1) { songService.rankedSearch(0, 10, "fin", true, false) }
        coVerify(exactly = 1) { albumService.rankedSearch(0, 10, "fin") }
        coVerify(exactly = 1) { artistService.rankedSearch(0, 10, "fin") }
        coVerify(exactly = 1) { userPlaylistService.rankedSearch(null, 0, 10, "fin") }
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(songs, model.songs.value)
        assertEquals(albums, model.albums.value)
        assertEquals(artists, model.artists.value)
        assertEquals(playlists, model.playlists.value)
        assertEquals(false, model.isSearching.value)
    }

    @Test
    fun aBlankQueryClearsAllListsWithoutCallingAService() = runTest {
        val model = createModel()
        model.search("fin")
        passDebounce()
        assertEquals(songs, model.songs.value)

        model.search("   ")

        assertTrue(model.songs.value.isEmpty())
        assertTrue(model.albums.value.isEmpty())
        assertTrue(model.artists.value.isEmpty())
        assertTrue(model.playlists.value.isEmpty())
        assertEquals(false, model.isSearching.value)
        passDebounce()
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun aBlankQueryCancelsAPendingSearch() = runTest {
        val model = createModel()

        model.search("fin")
        advanceTimeBy(200)
        runCurrent()
        model.search("")
        passDebounce()

        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertTrue(model.songs.value.isEmpty())
    }

    @Test
    fun lyricsModeCallsOnlyTheLyricsSearchAndEmptiesTheOtherLists() = runTest {
        val model = createModel()
        model.search("fin")
        passDebounce()
        assertEquals(albums, model.albums.value)

        model.setSearchMode(SearchMode.LYRICS)
        passDebounce()

        coVerify(exactly = 1) { songService.searchByLyrics(0, 50, "fin", true) }
        coVerify(exactly = 1) { albumService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { artistService.rankedSearch(any(), any(), any()) }
        coVerify(exactly = 1) { userPlaylistService.rankedSearch(any(), any(), any(), any()) }
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(lyricSongs, model.songs.value)
        assertTrue(model.albums.value.isEmpty())
        assertTrue(model.artists.value.isEmpty())
        assertTrue(model.playlists.value.isEmpty())
    }

    @Test
    fun changingTheModeReRunsTheLastQuery() = runTest {
        val model = createModel()
        model.search("fin")
        passDebounce()

        model.setSearchMode(SearchMode.LYRICS)
        passDebounce()

        assertEquals(SearchMode.LYRICS, model.searchMode.value)
        coVerify(exactly = 1) { songService.searchByLyrics(0, 50, "fin", true) }
    }

    @Test
    fun selectingTheCurrentModeAgainDoesNotSearch() = runTest {
        val model = createModel()
        model.search("fin")
        passDebounce()

        model.setSearchMode(SearchMode.STANDARD)
        passDebounce()

        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { songService.searchByLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun aFailingSearchResetsTheSearchingFlag() = runTest {
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } throws IOException("down")
        val model = createModel()

        model.search("fin")
        passDebounce()

        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(false, model.isSearching.value)
        assertTrue(model.songs.value.isEmpty())
    }

    @Test
    fun refreshWithABlankLastQueryMakesNoCall() = runTest {
        val model = createModel()

        model.refresh()
        runCurrent()

        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(false, model.isRefreshing.value)
    }

    @Test
    fun refreshSearchesTheLastQueryAgainWithoutTheDebounce() = runTest {
        val model = createModel()
        model.search("fin")
        passDebounce()

        model.refresh()
        runCurrent()

        coVerify(exactly = 2) { songService.rankedSearch(0, 10, "fin", true, false) }
        assertEquals(false, model.isRefreshing.value)
    }
}
