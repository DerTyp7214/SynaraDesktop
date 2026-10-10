package dev.dertyp.synara.viewmodels.podcasts

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastSource
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.PODCAST_CONTINUATION_NEWER
import dev.dertyp.synara.podcast.PodcastContinuation
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.PodcastProgressStore
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastShowScreenModelTest {

    private class RecordingProgressStore : PodcastProgressStore {
        override val latest = MutableStateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>(emptyMap())
        override val updates = MutableSharedFlow<PodcastEpisodeProgress>(extraBufferCapacity = 16)

        override fun record(progress: PodcastEpisodeProgress) {
            latest.update { it + (progress.episodeId to progress) }
            updates.tryEmit(progress)
        }
    }

    private val showId = randomPlatformUUID()
    private val show = PodcastShow(
        id = showId,
        source = PodcastSource.FEED,
        title = "Show",
        subscribed = true,
        createdAt = 0L,
        updatedAt = 0L
    )
    private val newestFirst = listOf(episode(3), episode(2), episode(1), episode(0))
    private val oldestFirst = newestFirst.reversed()
    private val lastPlayed = newestFirst[2]

    private val currentEpisode = MutableStateFlow<PodcastEpisode?>(null)
    private val store = RecordingProgressStore()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val player = mockk<PodcastPlayer>(relaxed = true) {
        every { currentEpisode } returns this@PodcastShowScreenModelTest.currentEpisode
    }
    private val service = mockk<IPodcastService> {
        coEvery { getShow(showId) } returns show
        coEvery { getEpisodes(showId, any(), any(), true) } returns PaginatedResponse(newestFirst, total = newestFirst.size)
        coEvery { getEpisodes(showId, any(), any(), false) } returns PaginatedResponse(oldestFirst, total = oldestFirst.size)
        coEvery { getLastPlayed(any()) } returns lastPlayed
        coEvery { getEpisodeWindow(any(), any(), any()) } returns emptyList()
    }

    private fun episode(
        index: Int,
        importState: PodcastImportState = PodcastImportState.NONE
    ) = PodcastEpisode(
        id = randomPlatformUUID(),
        showId = showId,
        showTitle = "Show",
        guid = "guid-$index",
        title = "Episode $index",
        publishedAt = index.toLong(),
        importState = importState,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun progress(
        episode: PodcastEpisode,
        updatedAt: Long,
        showId: PlatformUUID = episode.showId
    ) = PodcastEpisodeProgress(
        episodeId = episode.id,
        showId = showId,
        positionMs = updatedAt * 1_000L,
        completed = false,
        lastPlayedAt = updatedAt,
        updatedAt = updatedAt
    )

    private fun TestScope.createModel(): PodcastShowScreenModel {
        val model = PodcastShowScreenModel(
            showId,
            rpcServiceManager,
            service,
            store,
            player,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return model
    }

    private fun TestScope.recordRefreshFailures(model: PodcastShowScreenModel): List<Unit> {
        val failures = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.refreshFailed.collect { failures += it }
        }
        return failures
    }

    @Test
    fun aKnownShowIsLoadedWithItsEpisodesAndContinuation() = runTest {
        val model = createModel()

        assertEquals(show, model.state.value.show)
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.notFound)
        assertFalse(model.state.value.failed)
        assertEquals(newestFirst, model.episodes.state.value.episodes)
        assertTrue(model.state.value.continuationLoaded)
        assertEquals(PodcastContinuation.Continue(lastPlayed), model.state.value.continuation)
    }

    @Test
    fun anUnknownShowIsReportedAsNotFoundAndNoEpisodesAreLoaded() = runTest {
        coEvery { service.getShow(showId) } returns null

        val model = createModel()

        assertTrue(model.state.value.notFound)
        assertNull(model.state.value.show)
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.failed)
        assertFalse(model.episodes.state.value.loaded)
        coVerify(exactly = 0) { service.getEpisodes(any(), any(), any(), any()) }
        coVerify(exactly = 0) { service.getLastPlayed(any()) }
    }

    @Test
    fun aFailingFirstLoadSetsFailedAndLoadsNoEpisodes() = runTest {
        coEvery { service.getShow(showId) } throws IllegalStateException("down")

        val model = createModel()

        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.notFound)
        assertNull(model.state.value.show)
        coVerify(exactly = 0) { service.getEpisodes(any(), any(), any(), any()) }
    }

    @Test
    fun aFailingReloadKeepsTheHeldShowWithoutFailed() = runTest {
        val model = createModel()
        coEvery { service.getShow(showId) } throws IllegalStateException("down")

        model.retry()
        runCurrent()

        assertEquals(show, model.state.value.show)
        assertFalse(model.state.value.failed)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun retryAfterAFailedFirstLoadLoadsTheShowAndItsEpisodes() = runTest {
        coEvery { service.getShow(showId) } throws IllegalStateException("down")
        val model = createModel()
        coEvery { service.getShow(showId) } returns show

        model.retry()
        runCurrent()

        assertEquals(show, model.state.value.show)
        assertFalse(model.state.value.failed)
        assertFalse(model.state.value.isLoading)
        assertEquals(newestFirst, model.episodes.state.value.episodes)
        assertTrue(model.state.value.continuationLoaded)
    }

    @Test
    fun aBurstOfProgressForThisShowPatchesTheListAndResolvesTheContinuationOnceAfterASecond() = runTest {
        val model = createModel()
        val target = newestFirst[0]
        val first = progress(target, updatedAt = 1L)
        val second = progress(target, updatedAt = 2L)
        coVerify(exactly = 1) { service.getLastPlayed(any()) }
        coEvery { service.getLastPlayed(any()) } returns target.copy(progress = second)

        store.record(first)
        runCurrent()
        assertEquals(first, model.episodes.state.value.episodes[0].progress)

        advanceTimeBy(500)
        runCurrent()
        store.record(second)
        runCurrent()
        assertEquals(second, model.episodes.state.value.episodes[0].progress)
        assertEquals(newestFirst.drop(1), model.episodes.state.value.episodes.drop(1))

        advanceTimeBy(999)
        runCurrent()
        coVerify(exactly = 1) { service.getLastPlayed(any()) }
        assertEquals(PodcastContinuation.Continue(lastPlayed), model.state.value.continuation)

        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 2) { service.getLastPlayed(any()) }
        assertEquals(PodcastContinuation.Continue(target.copy(progress = second)), model.state.value.continuation)

        advanceTimeBy(10_000)
        runCurrent()
        coVerify(exactly = 2) { service.getLastPlayed(any()) }
    }

    @Test
    fun progressForAnotherShowIsIgnored() = runTest {
        val model = createModel()
        val foreign = progress(newestFirst[0], updatedAt = 1L, showId = randomPlatformUUID())

        store.updates.tryEmit(foreign)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(newestFirst, model.episodes.state.value.episodes)
        coVerify(exactly = 1) { service.getLastPlayed(any()) }
    }

    @Test
    fun changingTheOrderRefetchesTheEpisodesAndTheSameOrderDoesNothing() = runTest {
        val model = createModel()
        coVerify(exactly = 1) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }

        model.setNewestFirst(true)
        runCurrent()
        coVerify(exactly = 1) { service.getEpisodes(any(), any(), any(), any()) }

        model.setNewestFirst(false)
        runCurrent()
        assertFalse(model.state.value.newestFirst)
        assertEquals(oldestFirst, model.episodes.state.value.episodes)
        coVerify(exactly = 1) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, false) }

        model.setNewestFirst(false)
        runCurrent()
        coVerify(exactly = 2) { service.getEpisodes(any(), any(), any(), any()) }

        model.setNewestFirst(true)
        runCurrent()
        assertTrue(model.state.value.newestFirst)
        assertEquals(newestFirst, model.episodes.state.value.episodes)
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun playingFromANewestFirstListQueuesTheEpisodeAndTheNewerOnesOldestFirst() = runTest {
        val model = createModel()

        model.playFromList(2)

        verify(exactly = 1) { player.playEpisodes(listOf(newestFirst[2], newestFirst[1], newestFirst[0]), 0) }
        verify(exactly = 0) { player.togglePlayPause() }
    }

    @Test
    fun playingFromAnOldestFirstListQueuesTheEpisodeAndEverythingAfterIt() = runTest {
        val model = createModel()
        model.setNewestFirst(false)
        runCurrent()

        model.playFromList(1)

        verify(exactly = 1) { player.playEpisodes(listOf(oldestFirst[1], oldestFirst[2], oldestFirst[3]), 0) }
        verify(exactly = 0) { player.togglePlayPause() }
    }

    @Test
    fun playingTheCurrentEpisodeFromTheListTogglesPlayback() = runTest {
        val model = createModel()
        currentEpisode.value = newestFirst[1]

        model.playFromList(1)

        verify(exactly = 1) { player.togglePlayPause() }
        verify(exactly = 0) { player.playEpisodes(any(), any()) }
    }

    @Test
    fun playingAnIndexOutsideTheListDoesNothing() = runTest {
        val model = createModel()

        model.playFromList(newestFirst.size)
        model.playFromList(-1)

        verify(exactly = 0) { player.playEpisodes(any(), any()) }
        verify(exactly = 0) { player.togglePlayPause() }
    }

    @Test
    fun playingWithoutAContinuationDelegatesToPlayingTheShow() = runTest {
        coEvery { service.getLastPlayed(any()) } returns null
        coEvery { service.getEpisodes(showId, any(), any(), any()) } returns PaginatedResponse(emptyList())
        val model = createModel()
        assertTrue(model.state.value.continuationLoaded)
        assertNull(model.state.value.continuation)

        model.playContinuation()
        runCurrent()

        verify(exactly = 1) { player.playShow(showId) }
        verify(exactly = 0) { player.playEpisodes(any(), any()) }
    }

    @Test
    fun playingAContinuationQueuesItsWindowStartingAtTheEpisode() = runTest {
        coEvery {
            service.getEpisodeWindow(lastPlayed.id, 0, PODCAST_CONTINUATION_NEWER)
        } returns listOf(newestFirst[3], newestFirst[2], newestFirst[1], newestFirst[0])
        val model = createModel()

        model.playContinuation()
        runCurrent()

        verify(exactly = 1) { player.playEpisodes(listOf(newestFirst[2], newestFirst[1], newestFirst[0]), 0) }
        verify(exactly = 0) { player.playShow(any()) }
    }

    @Test
    fun theContinuationQueueCarriesTheLatestStoredProgress() = runTest {
        val stored = progress(newestFirst[1], updatedAt = 4L)
        store.latest.value = mapOf(stored.episodeId to stored)
        coEvery {
            service.getEpisodeWindow(lastPlayed.id, 0, PODCAST_CONTINUATION_NEWER)
        } returns listOf(newestFirst[2], newestFirst[1], newestFirst[0])
        val model = createModel()

        model.playContinuation()
        runCurrent()

        verify(exactly = 1) {
            player.playEpisodes(listOf(newestFirst[2], newestFirst[1].copy(progress = stored), newestFirst[0]), 0)
        }
    }

    @Test
    fun aFailingContinuationWindowFallsBackToTheSingleEpisode() = runTest {
        coEvery { service.getEpisodeWindow(any(), any(), any()) } throws IllegalStateException("down")
        val model = createModel()

        model.playContinuation()
        runCurrent()

        verify(exactly = 1) { player.playEpisodes(listOf(lastPlayed), 0) }
        verify(exactly = 0) { player.playShow(any()) }
    }

    @Test
    fun aShowChangeThatDoesNotAffectStorageUpdatesTheShowWithoutReloadingEpisodes() = runTest {
        val model = createModel()
        val renamed = show.copy(title = "Renamed", episodeCount = 9, subscribed = false, updatedAt = 7L)

        model.onShowChanged(renamed)
        runCurrent()

        assertEquals(renamed, model.state.value.show)
        coVerify(exactly = 1) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aChangedDeliveryModeReloadsTheEpisodes() = runTest {
        val model = createModel()
        val changed = show.copy(deliveryMode = PodcastDeliveryMode.IMPORT)

        model.onShowChanged(changed)
        runCurrent()

        assertEquals(changed, model.state.value.show)
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aChangedEpisodeLimitReloadsTheEpisodes() = runTest {
        val model = createModel()
        val changed = show.copy(keepEpisodes = 3)

        model.onShowChanged(changed)
        runCurrent()

        assertEquals(changed, model.state.value.show)
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aChangedRetentionReloadsTheEpisodes() = runTest {
        val model = createModel()
        val changed = show.copy(retention = PodcastRetention.UNLISTENED)

        model.onShowChanged(changed)
        runCurrent()

        assertEquals(changed, model.state.value.show)
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aChangedFetchTimeReloadsTheEpisodes() = runTest {
        val model = createModel()
        val changed = show.copy(lastFetchedAt = 5L)

        model.onShowChanged(changed)
        runCurrent()

        assertEquals(changed, model.state.value.show)
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }

        model.onShowChanged(changed)
        runCurrent()
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aChangeOfAnotherShowIsIgnored() = runTest {
        val model = createModel()
        val foreign = show.copy(id = randomPlatformUUID(), deliveryMode = PodcastDeliveryMode.IMPORT, lastFetchedAt = 5L)

        model.onShowChanged(foreign)
        runCurrent()

        assertEquals(show, model.state.value.show)
        coVerify(exactly = 1) { service.getEpisodes(any(), any(), any(), any()) }
    }

    @Test
    fun aSuccessfulRefreshTakesTheRefreshedShowAndReloadsEpisodesAndContinuation() = runTest {
        val refreshed = show.copy(episodeCount = 9, lastFetchedAt = 5L)
        coEvery { service.refreshShow(showId) } returns refreshed
        val model = createModel()
        val failures = recordRefreshFailures(model)

        model.refresh()
        runCurrent()

        assertEquals(refreshed, model.state.value.show)
        assertTrue(failures.isEmpty())
        assertFalse(model.isRefreshing.value)
        coVerify(exactly = 1) { service.getShow(showId) }
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
        coVerify(exactly = 2) { service.getLastPlayed(any()) }
    }

    @Test
    fun aFailingRefreshSignalsTheFailureAndFallsBackToReadingTheShow() = runTest {
        val stored = show.copy(episodeCount = 4)
        coEvery { service.refreshShow(showId) } throws IllegalStateException("feed down")
        val model = createModel()
        val failures = recordRefreshFailures(model)
        coEvery { service.getShow(showId) } returns stored

        model.refresh()
        runCurrent()

        assertEquals(1, failures.size)
        assertEquals(stored, model.state.value.show)
        assertFalse(model.state.value.failed)
        assertFalse(model.isRefreshing.value)
        coVerify(exactly = 2) { service.getShow(showId) }
        coVerify(exactly = 2) { service.getEpisodes(showId, 0, EpisodeListPager.DEFAULT_PAGE_SIZE, true) }
    }

    @Test
    fun aRefreshBeforeAuthenticationAsksTheServerForNothing() = runTest {
        val authenticated = CompletableDeferred<Unit>()
        coEvery { rpcServiceManager.awaitAuthentication() } coAnswers { authenticated.await() }
        coEvery { service.refreshShow(showId) } returns show
        val model = createModel()

        model.refresh()
        runCurrent()

        coVerify(exactly = 0) { service.refreshShow(any()) }
        coVerify(exactly = 0) { service.getShow(any()) }
        assertFalse(model.isRefreshing.value)
    }

    @Test
    fun unsubscribingFromAShowTheServerNoLongerReturnsKeepsItAsUnsubscribed() = runTest {
        coEvery { service.unsubscribe(showId) } returns true
        val model = createModel()
        coEvery { service.getShow(showId) } returns null

        model.unsubscribe()

        assertEquals(show.copy(subscribed = false), model.state.value.show)
        assertFalse(model.state.value.isUpdatingSubscription)
        coVerify(exactly = 1) { service.unsubscribe(showId) }
    }

    @Test
    fun unsubscribingTakesTheShowTheServerReturns() = runTest {
        val remaining = show.copy(subscribed = false, subscriberCount = 2)
        coEvery { service.unsubscribe(showId) } returns true
        val model = createModel()
        coEvery { service.getShow(showId) } returns remaining

        model.unsubscribe()

        assertEquals(remaining, model.state.value.show)
        assertFalse(model.state.value.isUpdatingSubscription)
    }

    @Test
    fun subscribingTakesTheShowTheServerReturns() = runTest {
        val unsubscribed = show.copy(subscribed = false)
        coEvery { service.getShow(showId) } returns unsubscribed
        coEvery { service.subscribeToShow(showId) } returns show
        val model = createModel()

        model.subscribe()

        assertEquals(show, model.state.value.show)
        assertFalse(model.state.value.isUpdatingSubscription)
    }

    @Test
    fun pendingImportsAreReadAgainEveryThreeSecondsAndReplacedInTheList() = runTest {
        val queued = episode(3, PodcastImportState.QUEUED)
        val importing = episode(2, PodcastImportState.IMPORTING)
        val imported = episode(1, PodcastImportState.IMPORTED)
        val plain = episode(0)
        val finished = queued.copy(importState = PodcastImportState.IMPORTED, imported = true)
        coEvery { service.getEpisodes(showId, any(), any(), true) } returns
            PaginatedResponse(listOf(queued, importing, imported, plain), total = 4)
        coEvery { service.getEpisode(queued.id) } returns finished
        coEvery { service.getEpisode(importing.id) } returns importing
        val model = createModel()

        backgroundScope.launch { model.pollImports() }
        runCurrent()
        advanceTimeBy(2_999)
        runCurrent()
        coVerify(exactly = 0) { service.getEpisode(any()) }

        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 1) { service.getEpisode(queued.id) }
        coVerify(exactly = 1) { service.getEpisode(importing.id) }
        coVerify(exactly = 0) { service.getEpisode(imported.id) }
        coVerify(exactly = 0) { service.getEpisode(plain.id) }
        assertEquals(listOf(finished, importing, imported, plain), model.episodes.state.value.episodes)

        advanceTimeBy(3_000)
        runCurrent()
        coVerify(exactly = 1) { service.getEpisode(queued.id) }
        coVerify(exactly = 2) { service.getEpisode(importing.id) }
    }

    @Test
    fun nothingIsReadWhileNoImportIsPending() = runTest {
        val imported = episode(1, PodcastImportState.IMPORTED)
        val failed = episode(2, PodcastImportState.FAILED)
        val plain = episode(0)
        coEvery { service.getEpisodes(showId, any(), any(), true) } returns
            PaginatedResponse(listOf(failed, imported, plain), total = 3)
        val model = createModel()

        backgroundScope.launch { model.pollImports() }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        coVerify(exactly = 0) { service.getEpisode(any()) }
    }

    @Test
    fun aFailingImportReadDoesNotStopTheOtherPendingEpisodesOrLaterRounds() = runTest {
        val queued = episode(3, PodcastImportState.QUEUED)
        val importing = episode(2, PodcastImportState.IMPORTING)
        val finished = importing.copy(importState = PodcastImportState.IMPORTED, imported = true)
        coEvery { service.getEpisodes(showId, any(), any(), true) } returns
            PaginatedResponse(listOf(queued, importing), total = 2)
        coEvery { service.getEpisode(queued.id) } throws IllegalStateException("down")
        coEvery { service.getEpisode(importing.id) } returns finished
        val model = createModel()

        backgroundScope.launch { model.pollImports() }
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        coVerify(exactly = 1) { service.getEpisode(queued.id) }
        coVerify(exactly = 1) { service.getEpisode(importing.id) }
        assertEquals(listOf(queued, finished), model.episodes.state.value.episodes)

        advanceTimeBy(3_000)
        runCurrent()
        coVerify(exactly = 2) { service.getEpisode(queued.id) }
        coVerify(exactly = 1) { service.getEpisode(importing.id) }
    }
}
