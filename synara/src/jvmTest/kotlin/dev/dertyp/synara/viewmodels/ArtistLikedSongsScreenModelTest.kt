package dev.dertyp.synara.viewmodels

import dev.dertyp.data.Artist
import dev.dertyp.data.LikeLevel
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtistLikedSongsScreenModelTest {

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

    private fun likedSong(index: Int) = testSong(index).copy(isFavourite = true, likeLevel = LikeLevel.LIKE)

    private fun createModel(dispatchers: SynaraDispatchers = UnconfinedDispatchers) = ArtistLikedSongsScreenModel(
        artistId,
        rpcServiceManager,
        artistService,
        songService,
        songCache,
        playerModel,
        dispatchers
    )

    private fun ArtistLikedSongsScreenModel.success() =
        assertIs<ArtistLikedSongsScreenModel.ArtistLikedSongsState.Success>(state.value)

    @Test
    fun theFirstPageLoadsWithTheArtistAndTheNextPageIsAppended() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(second, hasNext = false)

        val model = createModel()
        assertEquals(first, model.success().songs)
        assertEquals(artist, model.success().artist)
        model.loadSongs()
        model.loadSongs()

        assertEquals(first + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
        coVerify(exactly = 1) { songService.likedByArtist(1, 50, artistId, true) }
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedAndRequestedAgainOnTheFreshList() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(second, hasNext = true)
        val model = createModel()
        model.loadSongs()
        assertEquals(first + second, model.success().songs)

        val reloadGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        val pageGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.likedByArtist(0, 50, artistId, true) } coAnswers { reloadGate.await() }
        coEvery { songService.likedByArtist(2, 50, artistId, true) } coAnswers { pageGate.await() }
        model.refresh()
        assertEquals(true, model.isRefreshing.value)
        model.loadSongs()
        coVerify(exactly = 1) { songService.likedByArtist(2, 50, artistId, true) }
        reloadGate.complete(page(refreshed, hasNext = true))
        assertEquals(refreshed, model.success().songs)
        assertEquals(false, model.isRefreshing.value)

        coVerify(exactly = 1) { songService.likedByArtist(1, 50, artistId, true) }

        pageGate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.likedByArtist(1, 50, artistId, true) }
        coVerify(exactly = 1) { songService.likedByArtist(2, 50, artistId, true) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aPageInFlightWhenAReloadCompletesIsDropped() {
        val first = testSongs(2)
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } coAnswers { gate.await() }
        val model = createModel()
        model.loadSongs()

        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(refreshed, hasNext = false)
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
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } coAnswers {
            if (pageOneCalls++ == 0) gate.await() else page(second, hasNext = false)
        }
        val model = createModel()
        model.loadSongs()
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(refreshed, hasNext = true)
        model.refresh()
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.likedByArtist(1, 50, artistId, true) }
        assertEquals(refreshed + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aFailedReloadKeepsTheLoadedSongs() {
        val first = testSongs(2)
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = false)
        val model = createModel()

        coEvery { songService.likedByArtist(0, 50, artistId, true) } throws IOException("down")
        model.refresh()

        coVerify(exactly = 2) { songService.likedByArtist(0, 50, artistId, true) }
        assertEquals(first, model.success().songs)
        assertEquals(false, model.isRefreshing.value)
    }

    @Test
    fun aPageRequestedTheMomentTheListChangesIsLoadedOnceThePageInProgressIsDone() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val third = listOf(testSong(20))
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(second, hasNext = true)
        coEvery { songService.likedByArtist(2, 50, artistId, true) } returns page(third, hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(first, model.success().songs)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { model.loadSongs() } }
        runCurrent()

        assertEquals(first + second + third, model.success().songs)
        coVerify(exactly = 1) { songService.likedByArtist(1, 50, artistId, true) }
        coVerify(exactly = 1) { songService.likedByArtist(2, 50, artistId, true) }
    }

    @Test
    fun aPageRequestedTheMomentAReloadIsPublishedContinuesTheFreshList() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(first, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(second, hasNext = true)
        coEvery { songService.likedByArtist(2, 50, artistId, true) } returns page(listOf(testSong(30)), hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        model.loadSongs()
        runCurrent()
        assertEquals(first + second, model.success().songs)
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(refreshed, hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(second, hasNext = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { state ->
                if ((state as? ArtistLikedSongsScreenModel.ArtistLikedSongsState.Success)?.songs == refreshed) model.loadSongs()
            }
        }

        model.refresh()
        runCurrent()

        assertEquals(refreshed + second, model.success().songs)
        coVerify(exactly = 0) { songService.likedByArtist(2, 50, artistId, true) }
    }

    @Test
    fun aSongUnlikedInTheListKeepsItsRowAndTheFollowingPagesSkipNoSong() {
        val server = List(55) { likedSong(it) }
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(server.take(50), hasNext = true)
        val model = createModel()
        val unliked = server[10].copy(isFavourite = false, likeLevel = LikeLevel.NONE)
        val remaining = server - server[10]
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(remaining.take(50), hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(remaining.drop(50), hasNext = false)

        runBlocking { songCache.put(unliked) }
        model.loadSongs()
        model.loadSongs()

        assertEquals(server.take(10) + unliked + server.drop(11), model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aSongLikedAgainInTheListMovesTheFollowingPageBack() {
        val server = List(55) { likedSong(it) }
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(server.take(50), hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(server.drop(50), hasNext = false)
        val model = createModel()

        runBlocking { songCache.put(server[10].copy(isFavourite = false, likeLevel = LikeLevel.NONE)) }
        runBlocking { songCache.put(server[10]) }
        model.loadSongs()

        assertEquals(server, model.success().songs)
        coVerify(exactly = 1) { songService.likedByArtist(0, 50, artistId, true) }
    }

    @Test
    fun aPageThatRepeatsLoadedSongsShowsEachSongOnce() {
        val server = List(53) { likedSong(it) }
        coEvery { songService.likedByArtist(0, 50, artistId, true) } returns page(server.take(50), hasNext = true)
        coEvery { songService.likedByArtist(1, 50, artistId, true) } returns page(server.drop(49), hasNext = false)
        val model = createModel()

        model.loadSongs()

        assertEquals(server, model.success().songs)
    }
}
