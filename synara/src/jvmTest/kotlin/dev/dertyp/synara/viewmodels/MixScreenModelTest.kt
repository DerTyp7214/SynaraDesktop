package dev.dertyp.synara.viewmodels

import dev.dertyp.data.MoodSummary
import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.Config
import dev.dertyp.synara.mixes.MixSource
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.mixes.MixType
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSongs
import io.mockk.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixScreenModelTest {

    private val weekMix = MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), 50)
    private val topWeek = MixSpec.TopSongs(StatsRange.WEEK, TopOrder.LISTENED_MS, 25)
    private val songs = testSongs(3)
    private val otherSongs = testSongs(5).drop(3)
    private val moods = listOf(MoodSummary("chill", 12), MoodSummary("happy", 4))

    private val recommendationService = mockk<IRecommendationService> {
        coEvery { getMix(any(), any()) } returns songs
        coEvery { getMoodPlaylist(any(), any()) } returns otherSongs
        coEvery { getMoods() } returns moods
    }
    private val listeningStatsService = mockk<IListeningStatsService> {
        coEvery { getTopSongs(any(), any(), any(), any()) } returns songs
    }
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val models = mutableListOf<MixScreenModel>()

    @BeforeTest
    fun setUp() {
        startTestKoin()
        ConfigFixture.resetToDefaults()
    }

    @AfterTest
    fun tearDown() {
        models.forEach { it.onDispose() }
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun TestScope.createModel(spec: MixSpec = weekMix): MixScreenModel {
        val model = MixScreenModel(
            spec,
            recommendationService,
            listeningStatsService,
            rpcServiceManager,
            playerModel,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        models += model
        runCurrent()
        return model
    }

    @Test
    fun creatingTheModelLoadsTheSongsAndMoodsOfYourMix() = runTest {
        val model = createModel()

        assertEquals(songs, model.state.value.songs)
        assertEquals(moods, model.state.value.moods)
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.error)
        coVerify(exactly = 1) { recommendationService.getMix(RecommendationWindow.WEEK, 50) }
    }

    @Test
    fun topSongsLoadTheirRangeAndOrderButNoMoods() = runTest {
        val model = createModel(topWeek)

        assertEquals(songs, model.state.value.songs)
        assertEquals(emptyList(), model.state.value.moods)
        coVerify(exactly = 1) { listeningStatsService.getTopSongs(StatsRange.WEEK, any(), 25, TopOrder.LISTENED_MS) }
        coVerify(exactly = 0) { recommendationService.getMoods() }
    }

    @Test
    fun changingTheSourcePersistsTheOptionAndRefetches() = runTest {
        val model = createModel()
        val chill = weekMix.copy(source = MixSource.Mood("chill"))

        model.setSpec(chill)
        runCurrent()

        assertEquals(chill, Config.mixOptions.value[MixType.YOUR_MIX])
        assertEquals(chill, model.state.value.spec)
        assertEquals(otherSongs, model.state.value.songs)
        coVerify(exactly = 1) { recommendationService.getMoodPlaylist("chill", 50) }
    }

    @Test
    fun changingTheSizeAndOrderOfTopSongsRefetchesWithThem() = runTest {
        val model = createModel(topWeek)

        model.setSize(100)
        runCurrent()
        model.setSpec((model.state.value.spec as MixSpec.TopSongs).copy(order = TopOrder.LISTEN_COUNT))
        runCurrent()

        val expected = MixSpec.TopSongs(StatsRange.WEEK, TopOrder.LISTEN_COUNT, 100)
        assertEquals(expected, Config.mixOptions.value[MixType.TOP_SONGS])
        coVerify(exactly = 1) { listeningStatsService.getTopSongs(StatsRange.WEEK, any(), 100, TopOrder.LISTENED_MS) }
        coVerify(exactly = 1) { listeningStatsService.getTopSongs(StatsRange.WEEK, any(), 100, TopOrder.LISTEN_COUNT) }
    }

    @Test
    fun settingTheSameSpecDoesNotRefetch() = runTest {
        val model = createModel()

        model.setSpec(weekMix)
        runCurrent()

        coVerify(exactly = 1) { recommendationService.getMix(any(), any()) }
    }

    @Test
    fun togglingThePinAddsAndRemovesTheMix() = runTest {
        val model = createModel()

        model.togglePin()
        runCurrent()

        assertEquals(listOf<MixSpec>(weekMix), Config.pinnedMixes.value)
        assertTrue(model.state.value.isPinned)

        model.togglePin()
        runCurrent()

        assertEquals(emptyList(), Config.pinnedMixes.value)
        assertFalse(model.state.value.isPinned)
    }

    @Test
    fun theDownloadToggleOnlyWorksForPinnedMixes() = runTest {
        val model = createModel()

        model.toggleDownload()
        runCurrent()

        assertEquals(emptyList(), Config.downloadedMixes.value)
        assertFalse(model.state.value.isDownloaded)

        model.togglePin()
        model.toggleDownload()
        runCurrent()

        assertEquals(listOf<MixSpec>(weekMix), Config.downloadedMixes.value)
        assertTrue(model.state.value.isDownloaded)

        model.toggleDownload()
        runCurrent()

        assertEquals(emptyList(), Config.downloadedMixes.value)
        assertFalse(model.state.value.isDownloaded)
    }

    @Test
    fun unpinningAlsoDropsTheDownload() = runTest {
        val model = createModel()

        model.togglePin()
        model.toggleDownload()
        model.togglePin()
        runCurrent()

        assertEquals(emptyList(), Config.pinnedMixes.value)
        assertEquals(emptyList(), Config.downloadedMixes.value)
        assertFalse(model.state.value.isDownloaded)
    }

    @Test
    fun resizingAPinnedMixUpdatesThePinnedAndDownloadedEntries() = runTest {
        val model = createModel()
        model.togglePin()
        model.toggleDownload()

        model.setSize(10)
        runCurrent()

        val resized = weekMix.copy(size = 10)
        assertEquals(listOf<MixSpec>(resized), Config.pinnedMixes.value)
        assertEquals(listOf<MixSpec>(resized), Config.downloadedMixes.value)
        assertTrue(model.state.value.isPinned)
    }

    @Test
    fun switchingToAnotherWindowKeepsTheOldPinAndShowsTheNewOneUnpinned() = runTest {
        val model = createModel()
        model.togglePin()

        model.setSpec(weekMix.copy(source = MixSource.Recent(RecommendationWindow.DAY)))
        runCurrent()

        assertEquals(listOf<MixSpec>(weekMix), Config.pinnedMixes.value)
        assertFalse(model.state.value.isPinned)
    }

    @Test
    fun anEmptyMixIsNeitherLoadingNorAnError() = runTest {
        coEvery { recommendationService.getMix(any(), any()) } returns emptyList()

        val model = createModel()

        assertEquals(emptyList(), model.state.value.songs)
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun aFailingLoadSetsTheErrorAndStopsLoading() = runTest {
        coEvery { recommendationService.getMix(any(), any()) } throws IOException("down")

        val model = createModel()

        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.isLoading)
        assertEquals(emptyList(), model.state.value.songs)
    }

    @Test
    fun failingMoodsLeaveTheChipsEmptyButStillLoadTheMix() = runTest {
        coEvery { recommendationService.getMoods() } throws IOException("down")

        val model = createModel()

        assertEquals(emptyList(), model.state.value.moods)
        assertEquals(songs, model.state.value.songs)
        assertNull(model.state.value.error)
    }

    @Test
    fun playStartsTheMixAtTheGivenIndex() = runTest {
        val model = createModel()

        val queue = slot<PlaybackQueue>()

        model.play(2)

        verify { playerModel.playQueue(capture(queue), 2) }
        assertEquals(songs, queue.captured.songs())
        verify(exactly = 0) { playerModel.setShuffleMode(any()) }
    }

    @Test
    fun shuffleEnablesShuffleAndPlaysTheMix() = runTest {
        val model = createModel()

        val queue = slot<PlaybackQueue>()

        model.shuffle()

        verifyOrder {
            playerModel.setShuffleMode(true)
            playerModel.playQueue(capture(queue), any())
        }
        assertEquals(songs, queue.captured.songs())
    }

    @Test
    fun saveAsPlaylistCreatesAPlaylistWithTheMixSongs() = runTest {
        val model = createModel()

        val queue = slot<PlaybackQueue>()

        model.saveAsPlaylist("Your Mix · Week")

        verify { playerModel.createPlaylist("Your Mix · Week", capture(queue)) }
        assertEquals(songs, queue.captured.songs())
    }

    @Test
    fun playingAnEmptyMixDoesNothing() = runTest {
        coEvery { recommendationService.getMix(any(), any()) } returns emptyList()
        val model = createModel()

        model.play()
        model.shuffle()
        model.saveAsPlaylist("x")

        verify(exactly = 0) { playerModel.playQueue(any(), any()) }
        verify(exactly = 0) { playerModel.setShuffleMode(any()) }
        verify(exactly = 0) { playerModel.createPlaylist(any(), any()) }
    }

    private fun PlaybackQueue.songs() = items.map { (it as QueueEntry.Explicit).song }
}
