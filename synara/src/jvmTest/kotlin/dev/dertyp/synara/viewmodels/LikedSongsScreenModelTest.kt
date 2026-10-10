package dev.dertyp.synara.viewmodels

import dev.dertyp.data.LikeLevel
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserSong
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.services.IDownloadManager
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

class LikedSongsScreenModelTest {

    private val songCache = SongCache()
    private val songService = mockk<ISongService>()
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val downloadManager = mockk<IDownloadManager>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun page(songs: List<UserSong>, hasNext: Boolean) =
        PaginatedResponse(data = songs, total = songs.size, hasNextPage = hasNext)

    private fun likedSong(index: Int) = testSong(index).copy(
        isFavourite = true,
        likeLevel = LikeLevel.LIKE,
        userSongUpdatedAt = platformDateFromEpochMilliseconds(1_000_000L - index)
    )

    private fun superLikedSong(index: Int) = testSong(index).copy(
        isFavourite = true,
        likeLevel = LikeLevel.SUPER,
        superLikedAt = platformDateFromEpochMilliseconds(1_000_000L - index)
    )

    private fun createModel(dispatchers: SynaraDispatchers = UnconfinedDispatchers) = LikedSongsScreenModel(
        rpcServiceManager,
        songService,
        songCache,
        playerModel,
        downloadManager,
        dispatchers
    )

    private fun LikedSongsScreenModel.success() = assertIs<LikedSongsScreenModel.LikedSongsState.Success>(state.value)

    @Test
    fun theFirstPageLoadsAndTheNextPageIsAppended() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(second, hasNext = false)

        val model = createModel()
        assertEquals(first, model.success().songs)
        model.loadNextPage()
        model.loadNextPage()

        assertEquals(first + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
        coVerify(exactly = 2) { songService.likedSongs(any(), any(), any()) }
    }

    @Test
    fun aFilterChangeWhileAPageIsInFlightLoadsTheNewFilter() {
        val superSongs = testSongs(2)
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.likedSongs(0, 50, true) } coAnswers { gate.await() }
        coEvery { songService.superLikedSongs(0, 50, true) } returns page(superSongs, hasNext = false)
        val model = createModel()
        assertIs<LikedSongsScreenModel.LikedSongsState.Loading>(model.state.value)

        model.setFilter(LikedFilter.Super)

        coVerify(exactly = 1) { songService.superLikedSongs(0, 50, true) }
        assertEquals(superSongs, model.success().songs)

        gate.complete(page(testSongs(3), hasNext = true))

        assertEquals(superSongs, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
        model.loadNextPage()
        coVerify(exactly = 1) { songService.likedSongs(any(), any(), any()) }
        coVerify(exactly = 1) { songService.superLikedSongs(any(), any(), any()) }
    }

    @Test
    fun aFilterSwitchedThereAndBackWhileLoadingShowsTheLastSelection() {
        val all = testSongs(3)
        val gates = List(2) { CompletableDeferred<PaginatedResponse<UserSong>>() }
        val superGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var allCalls = 0
        coEvery { songService.likedSongs(0, 50, true) } coAnswers { gates[allCalls++].await() }
        coEvery { songService.superLikedSongs(0, 50, true) } coAnswers { superGate.await() }
        val model = createModel()

        model.setFilter(LikedFilter.Super)
        model.setFilter(LikedFilter.All)
        superGate.complete(page(testSongs(1), hasNext = false))
        gates[0].complete(page(testSongs(2), hasNext = false))
        assertIs<LikedSongsScreenModel.LikedSongsState.Loading>(model.state.value)

        gates[1].complete(page(all, hasNext = false))

        assertEquals(LikedFilter.All, model.filter.value)
        assertEquals(all, model.success().songs)
        coVerify(exactly = 2) { songService.likedSongs(0, 50, true) }
        coVerify(exactly = 1) { songService.superLikedSongs(0, 50, true) }
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedAndRequestedAgainOnTheFreshList() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(second, hasNext = true)
        val model = createModel()
        model.loadNextPage()
        assertEquals(first + second, model.success().songs)

        val reloadGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        val pageGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.likedSongs(0, 50, true) } coAnswers { reloadGate.await() }
        coEvery { songService.likedSongs(2, 50, true) } coAnswers { pageGate.await() }
        model.refresh()
        model.loadNextPage()
        coVerify(exactly = 1) { songService.likedSongs(2, 50, true) }
        reloadGate.complete(page(refreshed, hasNext = true))
        assertEquals(refreshed, model.success().songs)

        coVerify(exactly = 1) { songService.likedSongs(1, 50, true) }

        pageGate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.likedSongs(1, 50, true) }
        coVerify(exactly = 1) { songService.likedSongs(2, 50, true) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aPageInFlightWhenAReloadCompletesIsDroppedAndTheSamePageCanBeRequestedAgain() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var pageOneCalls = 0
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } coAnswers {
            if (pageOneCalls++ == 0) gate.await() else page(second, hasNext = false)
        }
        val model = createModel()
        model.loadNextPage()

        coEvery { songService.likedSongs(0, 50, true) } returns page(refreshed, hasNext = true)
        model.refresh()
        assertEquals(refreshed, model.success().songs)
        model.loadNextPage()
        assertEquals(refreshed + second, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        assertEquals(refreshed + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
        coVerify(exactly = 2) { songService.likedSongs(1, 50, true) }
    }

    @Test
    fun aPageDroppedByAReloadIsRequestedAgainWithoutAnotherScroll() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var pageOneCalls = 0
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } coAnswers {
            if (pageOneCalls++ == 0) gate.await() else page(second, hasNext = false)
        }
        val model = createModel()
        model.loadNextPage()
        coEvery { songService.likedSongs(0, 50, true) } returns page(refreshed, hasNext = true)
        model.refresh()
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.likedSongs(1, 50, true) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aPageOfTheOtherFilterThatArrivesLateRequestsNothing() {
        val first = testSongs(2)
        val superSongs = listOf(testSong(40), testSong(41))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } coAnswers { gate.await() }
        coEvery { songService.superLikedSongs(0, 50, true) } returns page(superSongs, hasNext = true)
        val model = createModel()
        model.loadNextPage()
        model.setFilter(LikedFilter.Super)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        assertEquals(superSongs, model.success().songs)
        coVerify(exactly = 1) { songService.superLikedSongs(any(), any(), any()) }
        coVerify(exactly = 2) { songService.likedSongs(any(), any(), any()) }
    }

    @Test
    fun aFailedReloadKeepsTheLoadedSongs() {
        val first = testSongs(2)
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = false)
        val model = createModel()

        coEvery { songService.likedSongs(0, 50, true) } throws IOException("down")
        model.refresh()

        coVerify(exactly = 2) { songService.likedSongs(0, 50, true) }
        assertEquals(first, model.success().songs)
    }

    @Test
    fun aFailingFirstPageShowsTheErrorState() {
        coEvery { songService.likedSongs(0, 50, true) } throws IOException("down")

        val model = createModel()

        assertIs<LikedSongsScreenModel.LikedSongsState.Error>(model.state.value)
    }

    @Test
    fun aPageRequestedTheMomentTheListChangesIsLoadedOnceThePageInProgressIsDone() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val third = listOf(testSong(20))
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(second, hasNext = true)
        coEvery { songService.likedSongs(2, 50, true) } returns page(third, hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(first, model.success().songs)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { model.loadNextPage() } }
        runCurrent()

        assertEquals(first + second + third, model.success().songs)
        coVerify(exactly = 1) { songService.likedSongs(1, 50, true) }
        coVerify(exactly = 1) { songService.likedSongs(2, 50, true) }
    }

    @Test
    fun aPageRequestedTheMomentAReloadIsPublishedContinuesTheFreshList() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        coEvery { songService.likedSongs(0, 50, true) } returns page(first, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(second, hasNext = true)
        coEvery { songService.likedSongs(2, 50, true) } returns page(listOf(testSong(30)), hasNext = false)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        model.loadNextPage()
        runCurrent()
        assertEquals(first + second, model.success().songs)
        coEvery { songService.likedSongs(0, 50, true) } returns page(refreshed, hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(second, hasNext = false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { state ->
                if ((state as? LikedSongsScreenModel.LikedSongsState.Success)?.songs == refreshed) model.loadNextPage()
            }
        }

        model.refresh()
        runCurrent()

        assertEquals(refreshed + second, model.success().songs)
        coVerify(exactly = 0) { songService.likedSongs(2, 50, true) }
    }

    @Test
    fun anUnlikedSongIsRemovedAndTheFollowingPagesSkipNoSong() {
        val server = List(60) { likedSong(it) }
        coEvery { songService.likedSongs(0, 50, true) } returns page(server.take(50), hasNext = true)
        val model = createModel()
        val remaining = server - server[10]
        coEvery { songService.likedSongs(0, 50, true) } returns page(remaining.take(50), hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(remaining.drop(50), hasNext = false)

        runBlocking { songCache.put(server[10].copy(isFavourite = false, likeLevel = LikeLevel.NONE)) }
        assertEquals(remaining.take(49), model.success().songs)
        model.loadNextPage()
        model.loadNextPage()

        assertEquals(remaining, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aNewLikeIsShownAtTheTopWithoutAReloadAndTheFollowingPageRepeatsNoSong() {
        val server = List(61) { likedSong(it) }
        coEvery { songService.likedSongs(0, 50, true) } returns page(server.drop(1).take(50), hasNext = true)
        val model = createModel()
        coEvery { songService.likedSongs(0, 50, true) } returns page(server.take(50), hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(server.drop(50), hasNext = false)

        runBlocking { songCache.put(server[0]) }
        songCache.notifyLikedSongsChanged()

        assertEquals(server.take(51), model.success().songs)
        coVerify(exactly = 1) { songService.likedSongs(0, 50, true) }
        model.loadNextPage()
        assertEquals(server, model.success().songs)
    }

    @Test
    fun aChangedSongOfAPageThatIsNotLoadedYetIsNotInserted() {
        val server = List(60) { likedSong(it) }
        coEvery { songService.likedSongs(0, 50, true) } returns page(server.take(50), hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(server.drop(50), hasNext = false)
        val model = createModel()

        runBlocking { songCache.put(server[55].copy(title = "Renamed")) }

        assertEquals(server.take(50), model.success().songs)
        model.loadNextPage()
        assertEquals(server, model.success().songs)
    }

    @Test
    fun aPageThatRepeatsLoadedSongsShowsEachSongOnce() {
        val server = List(53) { likedSong(it) }
        coEvery { songService.likedSongs(0, 50, true) } returns page(server.take(50), hasNext = true)
        coEvery { songService.likedSongs(1, 50, true) } returns page(server.drop(49), hasNext = false)
        val model = createModel()

        model.loadNextPage()

        assertEquals(server, model.success().songs)
    }

    @Test
    fun aSuperLikeEntersAtTheTopOfTheSuperLikedSongsAndLeavesWhenItIsWithdrawn() {
        val existing = listOf(superLikedSong(1), superLikedSong(2))
        coEvery { songService.likedSongs(0, 50, true) } returns page(emptyList(), hasNext = false)
        coEvery { songService.superLikedSongs(0, 50, true) } returns page(existing, hasNext = false)
        val model = createModel()
        model.setFilter(LikedFilter.Super)
        val added = superLikedSong(0)

        runBlocking { songCache.put(added) }
        assertEquals(listOf(added) + existing, model.success().songs)

        runBlocking { songCache.put(existing[0].copy(likeLevel = LikeLevel.LIKE, superLikedAt = null)) }
        assertEquals(listOf(added, existing[1]), model.success().songs)
        coVerify(exactly = 1) { songService.superLikedSongs(any(), any(), any()) }
    }
}
