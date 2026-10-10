package dev.dertyp.synara.viewmodels

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchResultScreenModelsTest {

    private val songService = mockk<ISongService>()
    private val first = fullPage(0)
    private val second = fullPage(100)
    private val refreshed = fullPage(200)

    private fun fullPage(start: Int) = List(20) { testSong(start + it) }

    private fun response(songs: List<UserSong>) = PaginatedResponse(data = songs, total = songs.size)

    private fun createModel(dispatchers: SynaraDispatchers = UnconfinedDispatchers) = SearchSongsViewModel(
        songService,
        SongCache(),
        mockk<Logger>(relaxed = true),
        dispatchers,
        "fin"
    )

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    @Test
    fun theFirstPageLoadsOnCreationAndLoadingMoreAppendsTheNextPage() {
        val last = listOf(testSong(100))
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(last)

        val model = createModel()
        assertEquals(first, model.items.value)
        assertEquals(true, model.hasNextPage.value)
        model.loadMore()
        model.loadMore()

        assertEquals(first + last, model.items.value)
        assertEquals(false, model.hasNextPage.value)
        assertEquals(false, model.isLoading.value)
        coVerify(exactly = 2) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedAndRequestedAgainOnTheFreshList() {
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(second)
        val model = createModel()
        model.loadMore()
        assertEquals(first + second, model.items.value)

        val reloadGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        val pageGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } coAnswers { reloadGate.await() }
        coEvery { songService.rankedSearch(2, 20, "fin", true, false) } coAnswers { pageGate.await() }
        model.refresh()
        assertEquals(true, model.isRefreshing.value)
        model.loadMore()
        coVerify(exactly = 1) { songService.rankedSearch(2, 20, "fin", true, false) }
        reloadGate.complete(response(refreshed))
        assertEquals(refreshed, model.items.value)
        assertEquals(false, model.isRefreshing.value)

        coVerify(exactly = 1) { songService.rankedSearch(1, 20, "fin", true, false) }

        pageGate.complete(response(fullPage(300)))

        coVerify(exactly = 2) { songService.rankedSearch(1, 20, "fin", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(2, 20, "fin", true, false) }
        assertEquals(refreshed + second, model.items.value)
        assertEquals(false, model.isLoading.value)
    }

    @Test
    fun aPageInFlightWhenAReloadCompletesIsDroppedAndRequestedAgainOnTheFreshList() {
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var pageCalls = 0
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } coAnswers {
            if (pageCalls++ == 0) gate.await() else response(second)
        }
        val model = createModel()
        model.loadMore()
        assertEquals(true, model.isLoading.value)

        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(refreshed)
        model.refresh()
        assertEquals(refreshed, model.items.value)

        gate.complete(response(fullPage(300)))

        coVerify(exactly = 2) { songService.rankedSearch(1, 20, "fin", true, false) }
        assertEquals(refreshed + second, model.items.value)
        assertEquals(false, model.isLoading.value)
    }

    @Test
    fun aFailingRefreshKeepsTheItemsAndReachesTheLogger() {
        val appLogger = startTestKoin().get<Logger>()
        val failure = IOException("down")
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        val model = createModel()

        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } throws failure
        model.refresh()

        assertEquals(first, model.items.value)
        assertEquals(false, model.isRefreshing.value)
        verify(exactly = 1) { appLogger.error(any(), any(), failure, any()) }
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(second)
        model.loadMore()
        assertEquals(first + second, model.items.value)
    }

    @Test
    fun aPageRequestedTheMomentTheListChangesIsLoadedOnceThePageInProgressIsDone() = runTest {
        val last = listOf(testSong(300))
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(second)
        coEvery { songService.rankedSearch(2, 20, "fin", true, false) } returns response(last)
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(first, model.items.value)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.items.collect { model.loadMore() } }
        runCurrent()

        assertEquals(first + second + last, model.items.value)
        assertEquals(false, model.hasNextPage.value)
        coVerify(exactly = 1) { songService.rankedSearch(1, 20, "fin", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(2, 20, "fin", true, false) }
    }

    @Test
    fun aPageRequestedTheMomentAReloadIsPublishedContinuesTheFreshList() = runTest {
        val last = listOf(testSong(400))
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(first)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(second)
        coEvery { songService.rankedSearch(2, 20, "fin", true, false) } returns response(fullPage(300))
        val model = createModel(TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        model.loadMore()
        runCurrent()
        assertEquals(first + second, model.items.value)
        coEvery { songService.rankedSearch(0, 20, "fin", true, false) } returns response(refreshed)
        coEvery { songService.rankedSearch(1, 20, "fin", true, false) } returns response(last)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.items.collect { items -> if (items == refreshed) model.loadMore() }
        }

        model.refresh()
        runCurrent()

        assertEquals(refreshed + last, model.items.value)
        assertEquals(false, model.isRefreshing.value)
        coVerify(exactly = 0) { songService.rankedSearch(2, 20, "fin", true, false) }
    }
}
