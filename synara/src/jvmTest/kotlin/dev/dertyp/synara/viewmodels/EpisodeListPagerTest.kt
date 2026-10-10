package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.podcast.PodcastProgressStore
import dev.dertyp.synara.viewmodels.podcasts.EpisodeListPager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeListPagerTest {

    private class MemoryProgressStore : PodcastProgressStore {
        private val _latest = MutableStateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>(emptyMap())
        override val latest: StateFlow<Map<PlatformUUID, PodcastEpisodeProgress>> = _latest
        override val updates: SharedFlow<PodcastEpisodeProgress> = MutableSharedFlow()

        override fun record(progress: PodcastEpisodeProgress) {
            _latest.value = _latest.value + (progress.episodeId to progress)
        }
    }

    private val showId = randomPlatformUUID()
    private val ids = List(6) { randomPlatformUUID() }
    private val store = MemoryProgressStore()
    private val requested = mutableListOf<Int>()
    private val script = ArrayDeque<suspend () -> PaginatedResponse<PodcastEpisode>>()

    private fun progress(index: Int, updatedAt: Long, positionMs: Long = 5_000) = PodcastEpisodeProgress(
        episodeId = ids[index],
        showId = showId,
        positionMs = positionMs,
        completed = false,
        lastPlayedAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun episode(index: Int, progress: PodcastEpisodeProgress? = null) = PodcastEpisode(
        id = ids[index],
        showId = showId,
        showTitle = "Show",
        guid = "guid-$index",
        title = "Episode $index",
        publishedAt = 1_000L + index,
        progress = progress,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    private fun page(total: Int, vararg indices: Int) =
        PaginatedResponse(data = indices.map { episode(it) }, total = total)

    private fun respond(response: PaginatedResponse<PodcastEpisode>) {
        script += { response }
    }

    private fun TestScope.createPager() = EpisodeListPager(
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        store,
        pageSize = 2
    ) { page, _ ->
        requested += page
        script.removeFirst().invoke()
    }

    private fun EpisodeListPager.episodeIds() = state.value.episodes.map { it.id }

    @Test
    fun aSuccessfulReloadMarksTheListLoadedAndKnowsWhetherThereIsMore() = runTest {
        respond(page(5, 0, 1, 2))
        val pager = createPager()

        pager.reload()

        val state = pager.state.value
        assertTrue(state.loaded)
        assertEquals(5, state.total)
        assertEquals(listOf(ids[0], ids[1], ids[2]), pager.episodeIds())
        assertTrue(state.hasMore)
        assertFalse(state.isLoading)
        assertFalse(state.failed)
    }

    @Test
    fun aCompleteListHasNoMore() = runTest {
        respond(page(2, 0, 1))
        val pager = createPager()

        pager.reload()

        assertFalse(pager.state.value.hasMore)
    }

    @Test
    fun newerStoredProgressIsOverlaidOnTheLoadedEpisodes() = runTest {
        val newer = progress(0, updatedAt = 500)
        store.record(newer)
        respond(page(2, 0, 1))
        val pager = createPager()

        pager.reload()

        assertEquals(newer, pager.state.value.episodes[0].progress)
        assertNull(pager.state.value.episodes[1].progress)
    }

    @Test
    fun storedProgressThatIsNotNewerThanTheEpisodeOwnProgressIsIgnored() = runTest {
        val own = progress(0, updatedAt = 900, positionMs = 42_000)
        store.record(progress(0, updatedAt = 500))
        respond(PaginatedResponse(data = listOf(episode(0, own)), total = 1))
        val pager = createPager()

        pager.reload()

        assertEquals(own, pager.state.value.episodes[0].progress)
    }

    @Test
    fun aFailedFirstLoadMarksTheListFailed() = runTest {
        script += { throw IOException("down") }
        val pager = createPager()

        pager.reload()

        val state = pager.state.value
        assertTrue(state.failed)
        assertFalse(state.isLoading)
        assertFalse(state.loaded)
    }

    @Test
    fun aFailedReloadWithContentKeepsTheEpisodesAndIsNotFailed() = runTest {
        respond(page(3, 0, 1))
        script += { throw IOException("down") }
        val pager = createPager()
        pager.reload()

        pager.reload()

        val state = pager.state.value
        assertFalse(state.failed)
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())
    }

    @Test
    fun loadingMoreAppendsOnlyUnknownEpisodes() = runTest {
        respond(page(4, 0, 1))
        respond(page(4, 1, 2))
        val pager = createPager()
        pager.reload()

        pager.loadMore()
        runCurrent()

        assertEquals(listOf(ids[0], ids[1], ids[2]), pager.episodeIds())
        assertEquals(4, pager.state.value.total)
        assertFalse(pager.state.value.isLoadingMore)
        assertEquals(listOf(0, 1), requested)
    }

    @Test
    fun anEmptyPageCollapsesTheTotalSoThereIsNoMore() = runTest {
        respond(page(4, 0, 1))
        respond(page(4))
        val pager = createPager()
        pager.reload()

        pager.loadMore()
        runCurrent()

        assertEquals(2, pager.state.value.total)
        assertFalse(pager.state.value.hasMore)
        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())
    }

    @Test
    fun aPageThatArrivesAfterAReloadCompletedIsDropped() = runTest {
        val gate = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        respond(page(4, 0, 1))
        script += { gate.await() }
        respond(page(4, 0, 1))
        val pager = createPager()
        pager.reload()
        pager.loadMore()
        runCurrent()
        assertTrue(pager.state.value.isLoadingMore)

        pager.reload()
        gate.complete(page(4, 2, 3))
        runCurrent()

        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())
        assertFalse(pager.state.value.isLoadingMore)
        respond(page(4, 2, 3))
        pager.loadMore()
        runCurrent()
        assertEquals(listOf(0, 1, 0, 1), requested)
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), pager.episodeIds())
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedWhenItArrivesAfterTheReload() = runTest {
        val answers = mutableMapOf<Int, suspend () -> PaginatedResponse<PodcastEpisode>>()
        val pager = EpisodeListPager(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            store,
            pageSize = 2
        ) { index, _ ->
            requested += index
            answers.getValue(index).invoke()
        }
        answers[0] = { page(6, 0, 1) }
        answers[1] = { page(6, 2, 3) }
        pager.reload()
        pager.loadMore()
        runCurrent()
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), pager.episodeIds())

        val reloadGate = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        val pageGate = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        answers[0] = { reloadGate.await() }
        answers[2] = { pageGate.await() }
        launch { pager.reload() }
        runCurrent()
        pager.loadMore()
        runCurrent()
        reloadGate.complete(page(6, 0, 1))
        runCurrent()
        pageGate.complete(page(6, 4, 5))
        runCurrent()

        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())
        assertFalse(pager.state.value.isLoadingMore)
        pager.loadMore()
        runCurrent()
        assertEquals(1, requested.last())
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), pager.episodeIds())
    }

    @Test
    fun theNewerOfTwoOverlappingReloadsWinsWhenTheOlderLandsLast() = runTest {
        val older = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        val newer = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        script += { older.await() }
        script += { newer.await() }
        val pager = createPager()

        launch { pager.reload() }
        runCurrent()
        launch { pager.reload() }
        runCurrent()
        newer.complete(page(2, 2, 3))
        runCurrent()
        older.complete(page(5, 0, 1))
        runCurrent()

        assertEquals(listOf(0, 0), requested)
        assertEquals(listOf(ids[2], ids[3]), pager.episodeIds())
        assertEquals(2, pager.state.value.total)
        assertTrue(pager.state.value.loaded)
    }

    @Test
    fun aFailureOfTheOlderOfTwoOverlappingReloadsThatLandsLastIsIgnored() = runTest {
        val older = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        val newer = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        script += { older.await() }
        script += { newer.await() }
        val pager = createPager()

        launch { pager.reload() }
        runCurrent()
        launch { pager.reload() }
        runCurrent()
        newer.complete(page(2, 2, 3))
        runCurrent()
        older.completeExceptionally(IOException("down"))
        runCurrent()

        val state = pager.state.value
        assertFalse(state.failed)
        assertTrue(state.loaded)
        assertFalse(state.isLoading)
        assertEquals(listOf(ids[2], ids[3]), pager.episodeIds())
    }

    @Test
    fun aFailedLoadMoreResetsTheFlagAndTheNextCallRequestsTheSamePage() = runTest {
        respond(page(4, 0, 1))
        script += { throw IOException("down") }
        respond(page(4, 2, 3))
        val pager = createPager()
        pager.reload()

        pager.loadMore()
        runCurrent()
        assertFalse(pager.state.value.isLoadingMore)
        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())

        pager.loadMore()
        runCurrent()

        assertEquals(listOf(0, 1, 1), requested)
        assertEquals(listOf(ids[0], ids[1], ids[2], ids[3]), pager.episodeIds())
    }

    @Test
    fun loadingMoreBeforeTheListIsLoadedDoesNothing() = runTest {
        val pager = createPager()

        pager.loadMore()
        runCurrent()

        assertTrue(requested.isEmpty())
        assertFalse(pager.state.value.isLoadingMore)
    }

    @Test
    fun loadingMoreWhileAPageIsAlreadyLoadingRequestsItOnce() = runTest {
        val gate = CompletableDeferred<PaginatedResponse<PodcastEpisode>>()
        respond(page(6, 0, 1))
        script += { gate.await() }
        val pager = createPager()
        pager.reload()

        pager.loadMore()
        runCurrent()
        pager.loadMore()
        runCurrent()

        assertEquals(listOf(0, 1), requested)
        assertTrue(pager.state.value.isLoadingMore)
        gate.complete(page(6, 2, 3))
        runCurrent()
        assertFalse(pager.state.value.isLoadingMore)
    }

    @Test
    fun ensureLoadedFetchesOnlyOnce() = runTest {
        respond(page(1, 0))
        val pager = createPager()

        pager.ensureLoaded()
        runCurrent()
        pager.ensureLoaded()
        runCurrent()

        assertEquals(listOf(0), requested)
        assertTrue(pager.state.value.loaded)
    }

    @Test
    fun appliedProgressUpdatesTheMatchingEpisode() = runTest {
        respond(page(2, 0, 1))
        val pager = createPager()
        pager.reload()
        val update = progress(1, updatedAt = 700)

        pager.applyProgress(update)

        assertNull(pager.state.value.episodes[0].progress)
        assertEquals(update, pager.state.value.episodes[1].progress)
    }

    @Test
    fun progressForAnUnknownEpisodeLeavesTheStateUntouched() = runTest {
        respond(page(2, 0, 1))
        val pager = createPager()
        pager.reload()
        val before = pager.state.value

        pager.applyProgress(progress(5, updatedAt = 700))

        assertEquals(before, pager.state.value)
    }

    @Test
    fun replacingAnEpisodeSwapsItInPlaceAndKeepsTheStoredProgress() = runTest {
        respond(page(2, 0, 1))
        val pager = createPager()
        pager.reload()
        val stored = progress(0, updatedAt = 800)
        store.record(stored)

        pager.replace(episode(0).copy(title = "Retitled"))

        val replaced = pager.state.value.episodes[0]
        assertEquals("Retitled", replaced.title)
        assertEquals(stored, replaced.progress)
        assertEquals(listOf(ids[0], ids[1]), pager.episodeIds())
    }

    @Test
    fun replacingAnUnknownEpisodeLeavesTheStateUntouched() = runTest {
        respond(page(2, 0, 1))
        val pager = createPager()
        pager.reload()
        val before = pager.state.value

        pager.replace(episode(5))

        assertEquals(before, pager.state.value)
    }
}
