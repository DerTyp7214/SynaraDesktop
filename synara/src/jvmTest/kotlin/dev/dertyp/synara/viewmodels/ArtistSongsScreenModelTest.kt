package dev.dertyp.synara.viewmodels

import dev.dertyp.data.Artist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.testing.testSongs
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtistSongsScreenModelTest {

    private val artistId = randomPlatformUUID()
    private val artist = Artist(artistId, "Artist", false)
    private val songCache = SongCache()
    private val songService = mockk<ISongService>()
    private val artistService = mockk<IArtistService> {
        coEvery { byId(artistId) } returns artist
    }
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun page(songs: List<UserSong>, hasNext: Boolean) =
        PaginatedResponse(data = songs, total = songs.size, hasNextPage = hasNext)

    private fun createModel(dispatchers: SynaraDispatchers = UnconfinedDispatchers) = ArtistSongsScreenModel(
        artistId,
        rpcServiceManager,
        artistService,
        songService,
        songCache,
        playerModel,
        dispatchers
    )

    private fun ArtistSongsScreenModel.success() =
        assertIs<ArtistSongsScreenModel.ArtistSongsState.Success>(state.value)

    @Test
    fun theFirstPageLoadsWithTheArtistAndTheNextPageIsAppended() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } returns page(second, hasNext = false)

        val model = createModel()
        assertEquals(first, model.success().songs)
        assertEquals(artist, model.success().artist)
        model.loadSongs()
        model.loadSongs()

        assertEquals(first + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
        coVerify(exactly = 1) { songService.byArtist(1, 50, artistId) }
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedAndRequestedAgainOnTheFreshList() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } returns page(second, hasNext = true)
        val model = createModel()
        model.loadSongs()
        assertEquals(first + second, model.success().songs)

        val reloadGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        val pageGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.byArtist(0, 50, artistId) } coAnswers { reloadGate.await() }
        coEvery { songService.byArtist(2, 50, artistId) } coAnswers { pageGate.await() }
        model.refresh()
        assertEquals(true, model.isRefreshing.value)
        model.loadSongs()
        coVerify(exactly = 1) { songService.byArtist(2, 50, artistId) }
        reloadGate.complete(page(refreshed, hasNext = true))
        assertEquals(refreshed, model.success().songs)
        assertEquals(false, model.isRefreshing.value)

        coVerify(exactly = 1) { songService.byArtist(1, 50, artistId) }

        pageGate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.byArtist(1, 50, artistId) }
        coVerify(exactly = 1) { songService.byArtist(2, 50, artistId) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aPageInFlightWhenAReloadCompletesIsDropped() {
        val first = testSongs(2)
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } coAnswers { gate.await() }
        val model = createModel()
        model.loadSongs()

        coEvery { songService.byArtist(0, 50, artistId) } returns page(refreshed, hasNext = false)
        model.refresh()
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        assertEquals(refreshed, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aPageDroppedByAReloadIsRequestedAgainWithoutAnotherScroll() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var pageOneCalls = 0
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } coAnswers {
            if (pageOneCalls++ == 0) gate.await() else page(second, hasNext = false)
        }
        val model = createModel()
        model.loadSongs()
        coEvery { songService.byArtist(0, 50, artistId) } returns page(refreshed, hasNext = true)
        model.refresh()
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.byArtist(1, 50, artistId) }
        assertEquals(refreshed + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aFailedReloadKeepsTheLoadedSongs() {
        val first = testSongs(2)
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = false)
        val model = createModel()

        coEvery { songService.byArtist(0, 50, artistId) } throws IOException("down")
        model.refresh()

        coVerify(exactly = 2) { songService.byArtist(0, 50, artistId) }
        assertEquals(first, model.success().songs)
        assertEquals(false, model.isRefreshing.value)
    }

    @Test
    fun aPageRequestedTheMomentTheListChangesIsLoadedOnceThePageInProgressIsDone() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val third = listOf(testSong(20))
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } returns page(second, hasNext = true)
        coEvery { songService.byArtist(2, 50, artistId) } returns page(third, hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(first, model.success().songs)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { model.loadSongs() } }
        runCurrent()

        assertEquals(first + second + third, model.success().songs)
        coVerify(exactly = 1) { songService.byArtist(1, 50, artistId) }
        coVerify(exactly = 1) { songService.byArtist(2, 50, artistId) }
    }

    @Test
    fun aPageRequestedTheMomentAReloadIsPublishedContinuesTheFreshList() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.byArtist(0, 50, artistId) } returns page(first, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } returns page(second, hasNext = true)
        coEvery { songService.byArtist(2, 50, artistId) } returns page(listOf(testSong(30)), hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        model.loadSongs()
        runCurrent()
        assertEquals(first + second, model.success().songs)
        coEvery { songService.byArtist(0, 50, artistId) } returns page(refreshed, hasNext = true)
        coEvery { songService.byArtist(1, 50, artistId) } returns page(second, hasNext = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { state ->
                if ((state as? ArtistSongsScreenModel.ArtistSongsState.Success)?.songs == refreshed) model.loadSongs()
            }
        }

        model.refresh()
        runCurrent()

        assertEquals(refreshed + second, model.success().songs)
        coVerify(exactly = 0) { songService.byArtist(2, 50, artistId) }
    }
}
