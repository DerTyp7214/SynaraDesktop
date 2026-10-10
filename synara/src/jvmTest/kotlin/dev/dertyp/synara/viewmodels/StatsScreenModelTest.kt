package dev.dertyp.synara.viewmodels

import dev.dertyp.data.LinkUnmatchedTrackRequest
import dev.dertyp.data.LinkUnmatchedTrackResult
import dev.dertyp.data.ListeningStats
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
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

class StatsScreenModelTest {

    private val stats = mockk<ListeningStats>()
    private val listeningStatsService = mockk<IListeningStatsService> {
        coEvery { getStats(any(), any(), any(), any()) } returns stats
    }
    private val songService = mockk<ISongService>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun TestScope.createModel(): StatsScreenModel {
        val model = StatsScreenModel(
            listeningStatsService,
            songService,
            rpcServiceManager,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return model
    }

    @Test
    fun everyRangeMapsToItsPeriod() {
        assertEquals(StatsPeriod.DAY, StatsRange.DAY.toPeriod())
        assertEquals(StatsPeriod.WEEK, StatsRange.WEEK.toPeriod())
        assertEquals(StatsPeriod.WEEK, StatsRange.LAST_WEEK.toPeriod())
        assertEquals(StatsPeriod.MONTH, StatsRange.MONTH.toPeriod())
        assertEquals(StatsPeriod.MONTH, StatsRange.LAST_MONTH.toPeriod())
        assertEquals(StatsPeriod.YEAR, StatsRange.YEAR.toPeriod())
        assertEquals(StatsPeriod.YEAR, StatsRange.LAST_YEAR.toPeriod())
        assertEquals(StatsPeriod.ALL_TIME, StatsRange.ALL_TIME.toPeriod())
    }

    @Test
    fun onlyTheLastRangesAreLastPeriods() {
        val last = setOf(StatsRange.LAST_WEEK, StatsRange.LAST_MONTH, StatsRange.LAST_YEAR)
        StatsRange.entries.forEach { assertEquals(it in last, it.isLastPeriod(), it.name) }
    }

    @Test
    fun aRangeSurvivesTheRoundTripThroughPeriodAndLast() {
        StatsRange.entries.forEach { range ->
            assertEquals(range, range.toPeriod().toRange(range.isLastPeriod()), range.name)
        }
    }

    @Test
    fun dayAndAllTimeIgnoreTheLastFlag() {
        assertEquals(StatsRange.DAY, StatsPeriod.DAY.toRange(true))
        assertEquals(StatsRange.ALL_TIME, StatsPeriod.ALL_TIME.toRange(true))
        assertEquals(StatsRange.LAST_WEEK, StatsPeriod.WEEK.toRange(true))
        assertEquals(StatsRange.WEEK, StatsPeriod.WEEK.toRange(false))
    }

    @Test
    fun creatingTheModelLoadsTheCurrentWeek() = runTest {
        val model = createModel()

        assertEquals(StatsRange.WEEK, model.state.value.selectedRange)
        assertEquals(stats, model.state.value.stats)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.error)
        coVerify(exactly = 1) { listeningStatsService.getStats(StatsRange.WEEK, any(), 50, TopOrder.LISTEN_COUNT) }
    }

    @Test
    fun aFailingLoadSetsTheErrorAndStopsLoading() = runTest {
        coEvery { listeningStatsService.getStats(any(), any(), any(), any()) } throws IOException("down")

        val model = createModel()

        assertNotNull(model.state.value.error)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.stats)
    }

    @Test
    fun selectingAPeriodKeepsTheLastFlagWhereSupportedAndDropsItForDay() = runTest {
        val model = createModel()

        model.setLast(true)
        runCurrent()
        assertEquals(StatsRange.LAST_WEEK, model.state.value.selectedRange)

        model.selectPeriod(StatsPeriod.MONTH)
        runCurrent()
        assertEquals(StatsRange.LAST_MONTH, model.state.value.selectedRange)

        model.selectPeriod(StatsPeriod.DAY)
        runCurrent()
        assertEquals(StatsRange.DAY, model.state.value.selectedRange)

        model.selectPeriod(StatsPeriod.WEEK)
        runCurrent()
        assertEquals(StatsRange.WEEK, model.state.value.selectedRange)
        coVerify(exactly = 2) { listeningStatsService.getStats(StatsRange.WEEK, any(), any(), any()) }
        coVerify(exactly = 1) { listeningStatsService.getStats(StatsRange.LAST_WEEK, any(), any(), any()) }
        coVerify(exactly = 1) { listeningStatsService.getStats(StatsRange.LAST_MONTH, any(), any(), any()) }
        coVerify(exactly = 1) { listeningStatsService.getStats(StatsRange.DAY, any(), any(), any()) }
        coVerify(exactly = 5) { listeningStatsService.getStats(any(), any(), any(), any()) }
    }

    @Test
    fun settingLastOnDayOrAllTimeMakesNoCall() = runTest {
        val model = createModel()
        model.selectPeriod(StatsPeriod.DAY)
        runCurrent()
        model.selectPeriod(StatsPeriod.ALL_TIME)
        runCurrent()

        model.setLast(true)
        runCurrent()
        model.selectPeriod(StatsPeriod.DAY)
        runCurrent()
        model.setLast(true)
        runCurrent()

        assertEquals(StatsRange.DAY, model.state.value.selectedRange)
        coVerify(exactly = 2) { listeningStatsService.getStats(StatsRange.DAY, any(), any(), any()) }
        coVerify(exactly = 1) { listeningStatsService.getStats(StatsRange.ALL_TIME, any(), any(), any()) }
        coVerify(exactly = 4) { listeningStatsService.getStats(any(), any(), any(), any()) }
    }

    @Test
    fun aSlowResponseForAnOlderRangeIsIgnoredAfterANewerLoad() = runTest {
        val weekStats = mockk<ListeningStats>()
        val monthStats = mockk<ListeningStats>()
        val weekGate = CompletableDeferred<ListeningStats>()
        coEvery { listeningStatsService.getStats(StatsRange.WEEK, any(), any(), any()) } coAnswers { weekGate.await() }
        coEvery { listeningStatsService.getStats(StatsRange.MONTH, any(), any(), any()) } returns monthStats
        val model = createModel()

        model.load(StatsRange.MONTH)
        runCurrent()
        assertEquals(monthStats, model.state.value.stats)

        weekGate.complete(weekStats)
        runCurrent()

        assertEquals(StatsRange.MONTH, model.state.value.selectedRange)
        assertEquals(monthStats, model.state.value.stats)
        assertEquals(false, model.state.value.isLoading)
    }

    @Test
    fun aLinkQueryUnderTwoCharactersClearsTheResultsWithoutACall() = runTest {
        val results = testSongs(2)
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } returns PaginatedResponse(data = results)
        val model = createModel()
        model.searchLinkTarget("ab")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(results, model.state.value.linkSearchResults)

        model.searchLinkTarget("a")

        assertTrue(model.state.value.linkSearchResults.isEmpty())
        advanceTimeBy(301)
        runCurrent()
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun theLinkSearchDebouncesAndOnlyTheLatestQueryIsSent() = runTest {
        val results = testSongs(1)
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } returns PaginatedResponse(data = results)
        val model = createModel()

        model.searchLinkTarget("ab")
        advanceTimeBy(100)
        runCurrent()
        model.searchLinkTarget("abc")
        advanceTimeBy(299)
        runCurrent()
        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }

        advanceTimeBy(2)
        runCurrent()

        coVerify(exactly = 1) { songService.rankedSearch(0, 20, "abc", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertEquals(results, model.state.value.linkSearchResults)
    }

    @Test
    fun linkingATrackReloadsTheStatsAndReturnsTheResult() = runTest {
        val songId = randomPlatformUUID()
        val msid = randomPlatformUUID()
        val mbid = randomPlatformUUID()
        val linked = LinkUnmatchedTrackResult(linkedListens = 3, submittedToListenBrainz = 1)
        coEvery { listeningStatsService.linkUnmatchedTrack(any()) } returns linked
        val model = createModel()

        val result = model.linkUnmatchedTrack(songId, msid, mbid)
        runCurrent()

        assertEquals(linked, result)
        coVerify(exactly = 1) { listeningStatsService.linkUnmatchedTrack(LinkUnmatchedTrackRequest(songId, msid, mbid)) }
        coVerify(exactly = 2) { listeningStatsService.getStats(StatsRange.WEEK, any(), any(), any()) }
    }

    @Test
    fun aFailedLinkReturnsNullAndSetsTheError() = runTest {
        coEvery { listeningStatsService.linkUnmatchedTrack(any()) } throws IllegalStateException("boom")
        val model = createModel()

        val result = model.linkUnmatchedTrack(randomPlatformUUID(), null, randomPlatformUUID())
        runCurrent()

        assertNull(result)
        assertNotNull(model.state.value.error)
        coVerify(exactly = 1) { listeningStatsService.getStats(any(), any(), any(), any()) }
    }
}
