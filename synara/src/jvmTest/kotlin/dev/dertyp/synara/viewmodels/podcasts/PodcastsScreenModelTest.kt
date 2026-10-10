package dev.dertyp.synara.viewmodels.podcasts

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastIndexInfo
import dev.dertyp.data.PodcastIndexResult
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastSource
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IPodcastService
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastsScreenModelTest {

    private class RecordingProgressStore : PodcastProgressStore {
        override val latest = MutableStateFlow<Map<PlatformUUID, PodcastEpisodeProgress>>(emptyMap())
        override val updates = MutableSharedFlow<PodcastEpisodeProgress>(extraBufferCapacity = 16)

        override fun record(progress: PodcastEpisodeProgress) {
            latest.update { it + (progress.episodeId to progress) }
            updates.tryEmit(progress)
        }
    }

    private val subscribedShows = listOf(show(0), show(1))
    private val latestEpisodes = listOf(episode(0), episode(1))
    private val inProgressEpisodes = listOf(episode(2))
    private val configuredIndex = PodcastIndexInfo(id = "index", name = "Index", configured = true)
    private val unconfiguredIndex = PodcastIndexInfo(id = "other", name = "Other", configured = false)
    private val directoryResults = listOf(
        PodcastIndexResult(indexId = "index", indexName = "Index", feedUrl = "https://feeds.invalid/a", title = "A"),
        PodcastIndexResult(indexId = "index", indexName = "Index", feedUrl = "https://feeds.invalid/b", title = "B")
    )

    private val currentEpisode = MutableStateFlow<PodcastEpisode?>(null)
    private val store = RecordingProgressStore()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val player = mockk<PodcastPlayer>(relaxed = true) {
        every { currentEpisode } returns this@PodcastsScreenModelTest.currentEpisode
    }
    private val service = mockk<IPodcastService> {
        coEvery { getSubscriptions() } returns subscribedShows
        coEvery { getLatestEpisodes(any(), any()) } returns PaginatedResponse(latestEpisodes, total = latestEpisodes.size)
        coEvery { getInProgress(any(), any()) } returns PaginatedResponse(inProgressEpisodes, total = inProgressEpisodes.size)
        coEvery { browseShows(any(), any(), any()) } returns PaginatedResponse(emptyList())
        coEvery { getIndexes() } returns emptyList()
        coEvery { searchIndex(any(), any(), any()) } returns emptyList()
    }

    private fun show(index: Int) = PodcastShow(
        id = randomPlatformUUID(),
        source = PodcastSource.FEED,
        title = "Show $index",
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun episode(index: Int) = PodcastEpisode(
        id = randomPlatformUUID(),
        showId = randomPlatformUUID(),
        showTitle = "Show",
        guid = "guid-$index",
        title = "Episode $index",
        publishedAt = index.toLong(),
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun TestScope.createModel(): PodcastsScreenModel {
        val model = PodcastsScreenModel(
            rpcServiceManager,
            service,
            store,
            player,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return model
    }

    private fun TestScope.openBrowse(model: PodcastsScreenModel) {
        model.selectTab(PodcastsTab.BROWSE)
        runCurrent()
    }

    private fun TestScope.type(model: PodcastsScreenModel, query: String) {
        model.setQuery(query)
        advanceTimeBy(PodcastsScreenModel.SEARCH_DEBOUNCE_MS)
        runCurrent()
    }

    @Test
    fun eachTabLoadsOnceOnItsFirstSelection() = runTest {
        val model = createModel()
        coVerify(exactly = 1) { service.getSubscriptions() }
        assertEquals(subscribedShows, model.subscriptions.value.shows)
        assertTrue(model.subscriptions.value.loaded)
        coVerify(exactly = 0) { service.getLatestEpisodes(any(), any()) }
        coVerify(exactly = 0) { service.getInProgress(any(), any()) }
        coVerify(exactly = 0) { service.browseShows(any(), any(), any()) }

        model.selectTab(PodcastsTab.LATEST)
        runCurrent()
        coVerify(exactly = 1) { service.getLatestEpisodes(0, EpisodeListPager.DEFAULT_PAGE_SIZE) }
        assertEquals(latestEpisodes, model.latest.state.value.episodes)

        model.selectTab(PodcastsTab.IN_PROGRESS)
        runCurrent()
        coVerify(exactly = 1) { service.getInProgress(0, EpisodeListPager.DEFAULT_PAGE_SIZE) }
        assertEquals(inProgressEpisodes, model.inProgress.state.value.episodes)

        model.selectTab(PodcastsTab.BROWSE)
        runCurrent()
        coVerify(exactly = 1) { service.browseShows("", 0, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        assertTrue(model.browse.value.loaded)

        listOf(PodcastsTab.SUBSCRIPTIONS, PodcastsTab.LATEST, PodcastsTab.IN_PROGRESS, PodcastsTab.BROWSE).forEach { tab ->
            model.selectTab(tab)
            runCurrent()
            assertEquals(tab, model.tab.value)
        }
        coVerify(exactly = 1) { service.getSubscriptions() }
        coVerify(exactly = 1) { service.getLatestEpisodes(any(), any()) }
        coVerify(exactly = 1) { service.getInProgress(any(), any()) }
        coVerify(exactly = 1) { service.browseShows(any(), any(), any()) }
    }

    @Test
    fun selectingTheCurrentTabAgainLoadsNothing() = runTest {
        coEvery { service.getSubscriptions() } throws IllegalStateException("down")
        val model = createModel()
        coVerify(exactly = 1) { service.getSubscriptions() }

        model.selectTab(PodcastsTab.SUBSCRIPTIONS)
        runCurrent()

        coVerify(exactly = 1) { service.getSubscriptions() }
    }

    @Test
    fun aTabSelectedBeforeAuthenticationLoadsAfterIt() = runTest {
        val authenticated = CompletableDeferred<Unit>()
        coEvery { rpcServiceManager.awaitAuthentication() } coAnswers { authenticated.await() }
        val model = createModel()

        model.selectTab(PodcastsTab.LATEST)
        runCurrent()
        coVerify(exactly = 0) { service.getLatestEpisodes(any(), any()) }
        coVerify(exactly = 0) { service.getSubscriptions() }

        authenticated.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { service.getLatestEpisodes(0, EpisodeListPager.DEFAULT_PAGE_SIZE) }
        coVerify(exactly = 0) { service.getSubscriptions() }
        assertEquals(latestEpisodes, model.latest.state.value.episodes)
    }

    @Test
    fun aQueryOnBrowseSearchesOnceAfterTheDebounceWithTheTrimmedText() = runTest {
        val model = createModel()
        openBrowse(model)

        model.setQuery("r")
        advanceTimeBy(100)
        runCurrent()
        model.setQuery("ro")
        advanceTimeBy(100)
        runCurrent()
        model.setQuery("  rock ")
        advanceTimeBy(PodcastsScreenModel.SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        coVerify(exactly = 1) { service.browseShows(any(), any(), any()) }

        advanceTimeBy(1)
        runCurrent()

        coVerify(exactly = 1) { service.browseShows("rock", 0, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        coVerify(exactly = 2) { service.browseShows(any(), any(), any()) }
        assertEquals("rock", model.browse.value.query)
    }

    @Test
    fun aQueryThatOnlyDiffersInSurroundingBlanksIsNotSearchedAgain() = runTest {
        val model = createModel()
        openBrowse(model)
        type(model, "rock")
        coVerify(exactly = 1) { service.browseShows("rock", any(), any()) }

        type(model, "  rock   ")

        coVerify(exactly = 1) { service.browseShows("rock", any(), any()) }
        coVerify(exactly = 2) { service.browseShows(any(), any(), any()) }
    }

    @Test
    fun aQueryTypedOnAnotherTabIsSearchedWhenSwitchingToBrowse() = runTest {
        val model = createModel()

        type(model, " jazz ")
        coVerify(exactly = 0) { service.browseShows(any(), any(), any()) }

        openBrowse(model)

        coVerify(exactly = 1) { service.browseShows("jazz", 0, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        coVerify(exactly = 1) { service.browseShows(any(), any(), any()) }
        assertEquals("jazz", model.browse.value.query)
    }

    @Test
    fun aQueryChangedWhileAwayFromBrowseIsSearchedOnReturn() = runTest {
        val model = createModel()
        openBrowse(model)
        model.selectTab(PodcastsTab.LATEST)
        runCurrent()

        type(model, "jazz")
        coVerify(exactly = 0) { service.browseShows("jazz", any(), any()) }

        openBrowse(model)

        coVerify(exactly = 1) { service.browseShows("jazz", 0, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
    }

    @Test
    fun aQueryBelowTwoCharactersLeavesTheDirectoryIdleWithoutAskingTheServer() = runTest {
        coEvery { service.getIndexes() } returns listOf(configuredIndex)
        coEvery { service.searchIndex(any(), any(), any()) } returns directoryResults
        val model = createModel()
        openBrowse(model)
        assertEquals(DirectoryState.Idle, model.directory.value)
        coVerify(exactly = 0) { service.getIndexes() }

        type(model, "ab")
        assertEquals(DirectoryState.Results(directoryResults), model.directory.value)

        type(model, "a")

        assertEquals(DirectoryState.Idle, model.directory.value)
        coVerify(exactly = 1) { service.getIndexes() }
        coVerify(exactly = 1) { service.searchIndex(any(), any(), any()) }
    }

    @Test
    fun theDirectoryIsUnavailableWhenNoIndexIsConfigured() = runTest {
        coEvery { service.getIndexes() } returns listOf(unconfiguredIndex)
        val model = createModel()
        openBrowse(model)

        type(model, "ab")

        assertEquals(DirectoryState.Unavailable, model.directory.value)
        coVerify(exactly = 0) { service.searchIndex(any(), any(), any()) }
    }

    @Test
    fun theDirectoryIsUnavailableWhenTheIndexesCannotBeReadAndTheyAreAskedAgainNextTime() = runTest {
        coEvery { service.getIndexes() } throws IllegalStateException("down")
        val model = createModel()
        openBrowse(model)

        type(model, "ab")
        assertEquals(DirectoryState.Unavailable, model.directory.value)
        coVerify(exactly = 0) { service.searchIndex(any(), any(), any()) }

        coEvery { service.getIndexes() } returns listOf(configuredIndex)
        coEvery { service.searchIndex(any(), any(), any()) } returns directoryResults
        type(model, "abc")

        coVerify(exactly = 2) { service.getIndexes() }
        assertEquals(DirectoryState.Results(directoryResults), model.directory.value)
    }

    @Test
    fun theDirectoryIsLoadingWhileTheSearchRunsAndThenHoldsTheResults() = runTest {
        val pending = CompletableDeferred<List<PodcastIndexResult>>()
        coEvery { service.getIndexes() } returns listOf(configuredIndex)
        coEvery { service.searchIndex(any(), any(), any()) } coAnswers { pending.await() }
        val model = createModel()
        openBrowse(model)

        type(model, "ab")
        assertEquals(DirectoryState.Loading, model.directory.value)
        coVerify(exactly = 1) { service.searchIndex("ab", PodcastsScreenModel.DIRECTORY_LIMIT, any()) }

        pending.complete(directoryResults)
        runCurrent()

        assertEquals(DirectoryState.Results(directoryResults), model.directory.value)
    }

    @Test
    fun aFailingDirectorySearchSetsTheFailedState() = runTest {
        coEvery { service.getIndexes() } returns listOf(configuredIndex)
        coEvery { service.searchIndex(any(), any(), any()) } throws IllegalStateException("down")
        val model = createModel()
        openBrowse(model)

        type(model, "ab")

        assertEquals(DirectoryState.Failed, model.directory.value)
    }

    @Test
    fun aStaleDirectorySearchDoesNotReplaceTheResultsOfANewerOne() = runTest {
        val slowIndexes = CompletableDeferred<List<PodcastIndexInfo>>()
        var indexRequests = 0
        coEvery { service.getIndexes() } coAnswers {
            if (indexRequests++ == 0) slowIndexes.await() else listOf(configuredIndex)
        }
        coEvery { service.searchIndex(any(), any(), any()) } returns directoryResults
        val model = createModel()
        openBrowse(model)
        type(model, "ab")
        assertEquals(1, indexRequests)

        model.refresh()
        runCurrent()
        assertEquals(DirectoryState.Results(directoryResults), model.directory.value)

        slowIndexes.complete(listOf(configuredIndex))
        runCurrent()

        assertEquals(DirectoryState.Results(directoryResults), model.directory.value)
    }

    @Test
    fun theIndexesAreReadOnceAndAgainAfterARefreshOnBrowse() = runTest {
        coEvery { service.getIndexes() } returns listOf(configuredIndex)
        coEvery { service.searchIndex(any(), any(), any()) } returns directoryResults
        val model = createModel()
        openBrowse(model)

        type(model, "ab")
        type(model, "abc")
        coVerify(exactly = 1) { service.getIndexes() }
        coVerify(exactly = 2) { service.searchIndex(any(), any(), any()) }

        model.refresh()
        runCurrent()

        coVerify(exactly = 2) { service.getIndexes() }
        coVerify(exactly = 2) { service.browseShows("abc", 0, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        assertFalse(model.isRefreshing.value)
    }

    @Test
    fun loadingMoreShowsAppendsOnlyUnknownShowsAndAnEmptyPageEndsTheList() = runTest {
        val first = show(10)
        val second = show(11)
        val third = show(12)
        coEvery { service.browseShows("", 0, any()) } returns PaginatedResponse(listOf(first, second), total = 5)
        coEvery { service.browseShows("", 1, any()) } returns PaginatedResponse(listOf(second, third), page = 1, total = 5)
        coEvery { service.browseShows("", 2, any()) } returns PaginatedResponse(emptyList(), page = 2, total = 5)
        val model = createModel()
        openBrowse(model)
        assertEquals(listOf(first, second), model.browse.value.shows)
        assertTrue(model.browse.value.hasMore)

        model.loadMoreShows()
        assertTrue(model.browse.value.isLoadingMore)
        runCurrent()

        assertEquals(listOf(first, second, third), model.browse.value.shows)
        assertEquals(5, model.browse.value.total)
        assertFalse(model.browse.value.isLoadingMore)
        assertTrue(model.browse.value.hasMore)

        model.loadMoreShows()
        runCurrent()

        assertEquals(listOf(first, second, third), model.browse.value.shows)
        assertEquals(3, model.browse.value.total)
        assertFalse(model.browse.value.hasMore)
        assertFalse(model.browse.value.isLoadingMore)

        model.loadMoreShows()
        runCurrent()

        coVerify(exactly = 1) { service.browseShows("", 1, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        coVerify(exactly = 1) { service.browseShows("", 2, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        coVerify(exactly = 0) { service.browseShows("", 3, any()) }
    }

    @Test
    fun aFailedLoadMoreKeepsTheShowsAndTheNextAttemptRequestsTheSamePage() = runTest {
        val first = show(10)
        val second = show(11)
        coEvery { service.browseShows("", 0, any()) } returns PaginatedResponse(listOf(first), total = 2)
        coEvery { service.browseShows("", 1, any()) } throws IllegalStateException("down")
        val model = createModel()
        openBrowse(model)

        model.loadMoreShows()
        runCurrent()
        assertEquals(listOf(first), model.browse.value.shows)
        assertFalse(model.browse.value.isLoadingMore)
        assertFalse(model.browse.value.failed)

        coEvery { service.browseShows("", 1, any()) } returns PaginatedResponse(listOf(second), page = 1, total = 2)
        model.loadMoreShows()
        runCurrent()

        coVerify(exactly = 2) { service.browseShows("", 1, PodcastsScreenModel.BROWSE_PAGE_SIZE) }
        assertEquals(listOf(first, second), model.browse.value.shows)
    }

    @Test
    fun aNewSearchCancelsARunningLoadMore() = runTest {
        val first = show(10)
        val late = show(11)
        val fresh = show(12)
        val pending = CompletableDeferred<PaginatedResponse<PodcastShow>>()
        coEvery { service.browseShows("", 0, any()) } returns PaginatedResponse(listOf(first), total = 5)
        coEvery { service.browseShows("", 1, any()) } coAnswers { pending.await() }
        coEvery { service.browseShows("new", 0, any()) } returns PaginatedResponse(listOf(fresh), total = 1)
        val model = createModel()
        openBrowse(model)

        model.loadMoreShows()
        runCurrent()
        assertTrue(model.browse.value.isLoadingMore)

        type(model, "new")
        assertEquals(listOf(fresh), model.browse.value.shows)
        assertFalse(model.browse.value.isLoadingMore)

        pending.complete(PaginatedResponse(listOf(late), page = 1, total = 5))
        runCurrent()

        assertEquals("new", model.browse.value.query)
        assertEquals(listOf(fresh), model.browse.value.shows)
        assertEquals(1, model.browse.value.total)
        assertFalse(model.browse.value.isLoadingMore)
    }

    @Test
    fun aFailingBrowseSearchSetsTheFailedState() = runTest {
        coEvery { service.browseShows(any(), any(), any()) } throws IllegalStateException("down")
        val model = createModel()

        openBrowse(model)

        assertTrue(model.browse.value.failed)
        assertFalse(model.browse.value.isLoading)
        assertFalse(model.browse.value.hasMore)
    }

    @Test
    fun showingTheScreenAgainReloadsLoadedSubscriptions() = runTest {
        val model = createModel()
        val updated = listOf(show(5))
        coEvery { service.getSubscriptions() } returns updated

        model.onShown()
        runCurrent()

        coVerify(exactly = 2) { service.getSubscriptions() }
        assertEquals(updated, model.subscriptions.value.shows)
    }

    @Test
    fun showingTheScreenAgainReloadsALoadedInProgressList() = runTest {
        val model = createModel()
        model.selectTab(PodcastsTab.IN_PROGRESS)
        runCurrent()
        val updated = listOf(episode(7), episode(8))
        coEvery { service.getInProgress(any(), any()) } returns PaginatedResponse(updated, total = updated.size)

        model.onShown()
        runCurrent()

        coVerify(exactly = 2) { service.getInProgress(0, EpisodeListPager.DEFAULT_PAGE_SIZE) }
        coVerify(exactly = 1) { service.getSubscriptions() }
        assertEquals(updated, model.inProgress.state.value.episodes)
    }

    @Test
    fun showingTheScreenAgainDoesNotReloadTheLatestOrBrowseTab() = runTest {
        val model = createModel()
        model.selectTab(PodcastsTab.LATEST)
        runCurrent()

        model.onShown()
        runCurrent()

        openBrowse(model)
        model.onShown()
        runCurrent()

        coVerify(exactly = 1) { service.getLatestEpisodes(any(), any()) }
        coVerify(exactly = 1) { service.browseShows(any(), any(), any()) }
        coVerify(exactly = 1) { service.getSubscriptions() }
        coVerify(exactly = 0) { service.getInProgress(any(), any()) }
    }

    @Test
    fun showingTheScreenAgainDoesNotReloadListsThatNeverLoaded() = runTest {
        coEvery { service.getSubscriptions() } throws IllegalStateException("down")
        coEvery { service.getInProgress(any(), any()) } throws IllegalStateException("down")
        val model = createModel()

        model.onShown()
        runCurrent()
        coVerify(exactly = 1) { service.getSubscriptions() }

        model.selectTab(PodcastsTab.IN_PROGRESS)
        runCurrent()
        model.onShown()
        runCurrent()

        coVerify(exactly = 1) { service.getInProgress(any(), any()) }
        coVerify(exactly = 1) { service.getSubscriptions() }
    }

    @Test
    fun showingTheScreenBeforeAuthenticationLoadsNothing() = runTest {
        val authenticated = CompletableDeferred<Unit>()
        coEvery { rpcServiceManager.awaitAuthentication() } coAnswers { authenticated.await() }
        val model = createModel()

        model.onShown()
        runCurrent()

        coVerify(exactly = 0) { service.getSubscriptions() }
        coVerify(exactly = 0) { service.getInProgress(any(), any()) }
    }

    @Test
    fun playingTheCurrentEpisodeTogglesPlayback() = runTest {
        val model = createModel()
        currentEpisode.value = latestEpisodes[1]

        model.playEpisodes(latestEpisodes, 1)

        verify(exactly = 1) { player.togglePlayPause() }
        verify(exactly = 0) { player.playEpisodes(any(), any()) }
    }

    @Test
    fun playingAnotherEpisodeStartsTheListAtItsIndex() = runTest {
        val model = createModel()
        currentEpisode.value = latestEpisodes[1]

        model.playEpisodes(latestEpisodes, 0)

        verify(exactly = 1) { player.playEpisodes(latestEpisodes, 0) }
        verify(exactly = 0) { player.togglePlayPause() }
    }

    @Test
    fun playingFromAnEmptyListOrAnIndexOutsideItDoesNothing() = runTest {
        val model = createModel()

        model.playEpisodes(emptyList(), 0)
        model.playEpisodes(latestEpisodes, latestEpisodes.size)
        model.playEpisodes(latestEpisodes, -1)

        verify(exactly = 0) { player.playEpisodes(any(), any()) }
        verify(exactly = 0) { player.togglePlayPause() }
    }

    @Test
    fun aFailingFirstSubscriptionLoadSetsFailed() = runTest {
        coEvery { service.getSubscriptions() } throws IllegalStateException("down")

        val model = createModel()

        assertTrue(model.subscriptions.value.failed)
        assertFalse(model.subscriptions.value.isLoading)
        assertFalse(model.subscriptions.value.loaded)
        assertTrue(model.subscriptions.value.shows.isEmpty())
    }

    @Test
    fun aFailingSubscriptionReloadKeepsTheContentWithoutFailed() = runTest {
        val model = createModel()
        coEvery { service.getSubscriptions() } throws IllegalStateException("down")

        model.refresh()
        runCurrent()

        coVerify(exactly = 2) { service.getSubscriptions() }
        assertFalse(model.subscriptions.value.failed)
        assertFalse(model.subscriptions.value.isLoading)
        assertTrue(model.subscriptions.value.loaded)
        assertEquals(subscribedShows, model.subscriptions.value.shows)
        assertFalse(model.isRefreshing.value)
    }

    @Test
    fun retryAfterAFailedSubscriptionLoadLoadsTheShows() = runTest {
        coEvery { service.getSubscriptions() } throws IllegalStateException("down")
        val model = createModel()
        coEvery { service.getSubscriptions() } returns subscribedShows

        model.retry()
        runCurrent()

        assertFalse(model.subscriptions.value.failed)
        assertTrue(model.subscriptions.value.loaded)
        assertEquals(subscribedShows, model.subscriptions.value.shows)
    }

    @Test
    fun aProgressUpdatePatchesTheLoadedEpisodeLists() = runTest {
        val model = createModel()
        model.selectTab(PodcastsTab.LATEST)
        runCurrent()
        val target = latestEpisodes[0]
        val progress = PodcastEpisodeProgress(
            episodeId = target.id,
            showId = target.showId,
            positionMs = 5_000L,
            completed = false,
            lastPlayedAt = 1L,
            updatedAt = 1L
        )

        store.record(progress)
        runCurrent()

        assertEquals(progress, model.latest.state.value.episodes[0].progress)
        assertEquals(latestEpisodes[1], model.latest.state.value.episodes[1])
    }
}
