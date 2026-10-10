package dev.dertyp.synara.podcast

import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.utils.compress
import dev.dertyp.synara.utils.decompress
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
class PodcastPlayerModelTest {

    private companion object {
        const val DEVICE_ID = "this-device"
        const val OTHER_DEVICE_ID = "other-device"
        const val STATE_FILE_NAME = "podcast_player_state.cbor.zstd"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val dispatchers = TestDispatchers(dispatcher)
    private val collectorScope = CoroutineScope(dispatcher + Job())
    private val stateDir: File = Files.createTempDirectory("podcast-player-model-test").toFile()
    private val stateFile: File get() = File(stateDir, STATE_FILE_NAME)

    private val engine = FakePodcastAudioEngine()
    private val store = InMemoryProgressStore()
    private val podcastService = mockk<IPodcastService>()
    private val logger = mockk<Logger>(relaxed = true)
    private val volume = MutableStateFlow(1f)
    private val outputDevice = MutableStateFlow<String?>(null)
    private val reports = mutableListOf<EpisodePlaybackReport>()
    private val errors = mutableListOf<PodcastPlayerError>()
    private var reportClock = 0L

    private lateinit var playerModel: PlayerModel
    private lateinit var rpcServiceManager: RpcServiceManager
    private lateinit var reporter: PodcastProgressReporter

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setPodcastsEnabled(true)
        Config.setPodcastPlaybackSpeed(1f)

        playerModel = mockk(relaxed = true)
        every { playerModel.volume } returns volume
        every { playerModel.currentOutputDevice } returns outputDevice

        rpcServiceManager = mockk()
        coJustRun { rpcServiceManager.awaitAuthentication() }

        coEvery { podcastService.reportPlayback(any()) } answers {
            val report = firstArg<EpisodePlaybackReport>()
            reports += report
            reportClock++
            PodcastEpisodeProgress(
                episodeId = report.episodeId,
                showId = randomPlatformUUID(),
                positionMs = report.positionMs,
                durationMs = report.durationMs,
                completed = report.completed,
                lastPlayedAt = reportClock,
                updatedAt = reportClock,
                deviceId = report.deviceId
            )
        }

        reporter = PodcastProgressReporter(
            podcastService = podcastService,
            progressStore = store,
            deviceIdentity = mockk<DeviceIdentity> { every { deviceId } returns DEVICE_ID },
            logger = logger,
            dispatchers = dispatchers
        )
    }

    @AfterTest
    fun tearDown() {
        collectorScope.cancel()
        ConfigFixture.resetToDefaults()
        stopTestKoin()
        stateDir.deleteRecursively()
    }

    private fun createModel(): PodcastPlayerModel {
        val model = PodcastPlayerModel(
            engine = engine,
            podcastService = podcastService,
            progressStore = store,
            reporter = reporter,
            playerModel = playerModel,
            rpcServiceManager = rpcServiceManager,
            cbor = AppCbor,
            logger = logger,
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )
        collectorScope.launch { model.errors.collect { errors += it } }
        scheduler.runCurrent()
        return model
    }

    private fun loading(episodes: List<PodcastEpisode>, startIndex: Int = 0): PodcastPlayerModel {
        val model = createModel()
        model.playEpisodes(episodes, startIndex)
        scheduler.runCurrent()
        return model
    }

    private fun playing(episodes: List<PodcastEpisode>, startIndex: Int = 0): PodcastPlayerModel {
        val model = loading(episodes, startIndex)
        engine.completeLoad()
        scheduler.runCurrent()
        return model
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun moveEngineTo(positionMs: Long) {
        engine.position.value = positionMs
        scheduler.runCurrent()
    }

    private fun sentPositions() = reports.map { it.episodeId to it.positionMs }

    private fun queueIds(model: PodcastPlayerModel) = model.queue.value.map { it.id }

    private fun writeState(state: PodcastPlayerState) {
        stateFile.writeBytes(compress(AppCbor.encodeToByteArray(state)))
    }

    private fun readState(): PodcastPlayerState =
        AppCbor.decodeFromByteArray<PodcastPlayerState>(decompress(stateFile.readBytes()))

    @Test
    fun playEpisodeResumesAtTheStoredPositionAndReportsIt() {
        val episode = testEpisode(1)
        store.record(testProgress(episode, positionMs = 90_000, updatedAt = 100))
        val model = createModel()

        model.playEpisode(episode)
        scheduler.runCurrent()

        assertEquals(listOf(FakePodcastAudioEngine.Load(episode.id, 90_000, true)), engine.loads)
        assertEquals(listOf(EpisodePlaybackReport(episode.id, 90_000, HOUR_MS, false, DEVICE_ID)), reports)
        assertEquals(90_000L, model.position.value)
        assertTrue(model.isLoading.value)
        assertFalse(model.isPlaying.value)

        engine.completeLoad()
        scheduler.runCurrent()

        assertFalse(model.isLoading.value)
        assertTrue(model.isPlaying.value)
        assertEquals(90_000L, model.position.value)
        assertEquals(1, engine.loads.size)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun playEpisodeStartsACompletedEpisodeFromTheBeginning() {
        val episode = testEpisode(1)
        store.record(testProgress(episode, positionMs = 3_500_000, updatedAt = 100, completed = true))
        val model = createModel()

        model.playEpisode(episode)
        scheduler.runCurrent()

        assertEquals(listOf(FakePodcastAudioEngine.Load(episode.id, 0, true)), engine.loads)
    }

    @Test
    fun playEpisodeWithAnExplicitStartIgnoresTheStoredPosition() {
        val episode = testEpisode(1)
        store.record(testProgress(episode, positionMs = 90_000, updatedAt = 100))
        val model = createModel()

        model.playEpisode(episode, startMs = 5_000)
        scheduler.runCurrent()

        assertEquals(listOf(FakePodcastAudioEngine.Load(episode.id, 5_000, true)), engine.loads)
        assertEquals(listOf(episode.id to 5_000L), sentPositions())
    }

    @Test
    fun progressEmbeddedInTheEpisodeWinsOverAnOlderStoreEntry() {
        val bare = testEpisode(1)
        val episode = bare.copy(progress = testProgress(bare, positionMs = 120_000, updatedAt = 200))
        store.record(testProgress(bare, positionMs = 90_000, updatedAt = 100))
        val model = createModel()

        model.playEpisode(episode)
        scheduler.runCurrent()

        assertEquals(listOf(FakePodcastAudioEngine.Load(episode.id, 120_000, true)), engine.loads)
    }

    @Test
    fun playbackIsReportedEveryTenSecondsUntilPaused() {
        val episode = testEpisode(1)
        val model = createModel()
        model.playEpisode(episode)
        scheduler.runCurrent()
        assertEquals(1, reports.size)

        advance(30_000)
        assertEquals(1, reports.size)

        engine.completeLoad()
        scheduler.runCurrent()
        assertEquals(1, reports.size)

        advance(9_999)
        assertEquals(1, reports.size)

        engine.position.value = 10_000
        advance(1)
        assertEquals(2, reports.size)

        engine.position.value = 20_000
        advance(10_000)
        assertEquals(3, reports.size)

        advance(4_999)
        assertEquals(3, reports.size)

        model.pause()
        scheduler.runCurrent()
        assertEquals(4, reports.size)

        advance(30_000)
        assertEquals(
            listOf(episode.id to 0L, episode.id to 10_000L, episode.id to 20_000L, episode.id to 20_000L),
            sentPositions()
        )
        assertEquals(1, engine.pauseCalls)
    }

    @Test
    fun finishingNearTheEndCompletesTheEpisodeAndLoadsTheNextOne() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        engine.duration.value = 60 * MINUTE_MS
        moveEngineTo(58 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertEquals(
            listOf(EpisodePlaybackReport(first.id, 60 * MINUTE_MS, 60 * MINUTE_MS, true, DEVICE_ID)),
            reports.filter { it.completed }
        )
        assertEquals(1, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(second.id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun finishingInTheMiddleKeepsThePositionAndReloadsThereOnPlay() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        engine.duration.value = 60 * MINUTE_MS
        moveEngineTo(30 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertTrue(reports.none { it.completed })
        assertEquals(first.id to 30 * MINUTE_MS, sentPositions().last())
        assertEquals(1, engine.stopCalls)
        assertEquals(1, engine.loads.size)
        assertEquals(0, model.currentIndex.value)
        assertEquals(30 * MINUTE_MS, model.position.value)
        assertEquals(listOf<PodcastPlayerError>(PodcastPlayerError.Failed(null)), errors)

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(first.id, 30 * MINUTE_MS, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun finishingWithUnknownDurationCompletesTheEpisodeAndLoadsTheNextOne() {
        val first = testEpisode(1, durationMs = null)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        moveEngineTo(5 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertEquals(
            listOf(EpisodePlaybackReport(first.id, 5 * MINUTE_MS, null, true, DEVICE_ID)),
            reports.filter { it.completed }
        )
        assertEquals(0, engine.stopCalls)
        assertEquals(1, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(second.id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun finishingTheLastEpisodeWithUnknownDurationCompletesItAndRewinds() {
        val episode = testEpisode(1, durationMs = null)
        val model = playing(listOf(episode))
        moveEngineTo(5 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertEquals(listOf(episode.id), reports.filter { it.completed }.map { it.episodeId })
        assertEquals(0, model.currentIndex.value)
        assertEquals(0L, model.position.value)
        assertEquals(1, engine.loads.size)
        assertTrue(errors.isEmpty())

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(episode.id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun aStreamErrorWithUnknownDurationKeepsThePositionAndReloadsThereOnPlay() {
        val episode = testEpisode(1, durationMs = null)
        val model = playing(listOf(episode))
        moveEngineTo(5 * MINUTE_MS)

        engine.emitError(PodcastPlayerError.Failed("stream"))
        scheduler.runCurrent()

        assertTrue(reports.none { it.completed })
        assertEquals(EpisodePlaybackReport(episode.id, 5 * MINUTE_MS, null, false, DEVICE_ID), reports.last())
        assertEquals(5 * MINUTE_MS, model.position.value)
        assertEquals(listOf<PodcastPlayerError>(PodcastPlayerError.Failed("stream")), errors)

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(episode.id, 5 * MINUTE_MS, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun finishingNearTheEndOfADurationOnlyTheEngineKnowsCompletesTheEpisode() {
        val first = testEpisode(1, durationMs = null)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        engine.duration.value = 60 * MINUTE_MS
        moveEngineTo(58 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertEquals(
            listOf(EpisodePlaybackReport(first.id, 60 * MINUTE_MS, 60 * MINUTE_MS, true, DEVICE_ID)),
            reports.filter { it.completed }
        )
        assertEquals(1, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(second.id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun finishingInTheMiddleOfADurationOnlyTheEngineKnowsKeepsThePosition() {
        val first = testEpisode(1, durationMs = null)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        engine.duration.value = 60 * MINUTE_MS
        moveEngineTo(30 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertTrue(reports.none { it.completed })
        assertEquals(0, model.currentIndex.value)
        assertEquals(30 * MINUTE_MS, model.position.value)
        assertEquals(1, engine.loads.size)
        assertEquals(listOf<PodcastPlayerError>(PodcastPlayerError.Failed(null)), errors)
    }

    @Test
    fun finishingTheLastEpisodeRewindsAndPlayReloadsFromTheStart() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        engine.duration.value = 60 * MINUTE_MS
        moveEngineTo(59 * MINUTE_MS)

        engine.emitFinished()
        scheduler.runCurrent()

        assertEquals(listOf(episode.id), reports.filter { it.completed }.map { it.episodeId })
        assertEquals(0, model.currentIndex.value)
        assertEquals(0L, model.position.value)
        assertEquals(1, engine.loads.size)

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(episode.id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun stopKeepsThePositionAndSeekingWhileStoppedOnlyMovesTheShownPosition() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        moveEngineTo(42_000)

        model.stop()
        scheduler.runCurrent()

        assertEquals(1, engine.stopCalls)
        assertEquals(0L, engine.position.value)
        assertEquals(42_000L, model.position.value)
        assertFalse(model.isPlaying.value)

        model.seekTo(70_000)
        scheduler.runCurrent()

        assertTrue(engine.seeks.isEmpty())
        assertEquals(70_000L, model.position.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(episode.id, 70_000, true), engine.loads.last())
    }

    @Test
    fun seekToIsClampedToTheDuration() {
        val episode = testEpisode(1, durationMs = HOUR_MS)
        val model = playing(listOf(episode))

        model.seekTo(HOUR_MS + 400_000)
        model.seekTo(-5_000)
        scheduler.runCurrent()

        assertEquals(listOf(HOUR_MS, 0L), engine.seeks)
        assertEquals(listOf(episode.id to 0L, episode.id to HOUR_MS, episode.id to 0L), sentPositions())
    }

    @Test
    fun skipPreviousPastTheRestartThresholdSeeksToTheStart() {
        val episodes = listOf(testEpisode(1), testEpisode(2))
        val model = playing(episodes, startIndex = 1)
        moveEngineTo(5_000)

        model.skipPrevious()
        scheduler.runCurrent()

        assertEquals(listOf(0L), engine.seeks)
        assertEquals(1, model.currentIndex.value)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun skipPreviousNearTheStartPlaysThePreviousEpisode() {
        val episodes = listOf(testEpisode(1), testEpisode(2))
        val model = playing(episodes, startIndex = 1)
        moveEngineTo(1_000)

        model.skipPrevious()
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(episodes[0].id, 0, true), engine.loads.last())
        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun skipPreviousNearTheStartOfTheFirstEpisodeSeeksToTheStart() {
        val episodes = listOf(testEpisode(1), testEpisode(2))
        val model = playing(episodes)
        moveEngineTo(1_000)

        model.skipPrevious()
        scheduler.runCurrent()

        assertEquals(listOf(0L), engine.seeks)
        assertEquals(0, model.currentIndex.value)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun removingTheCurrentEpisodeWhilePausedLoadsItsSuccessorPaused() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = createModel()
        model.playEpisodes(episodes, 0)
        scheduler.runCurrent()
        engine.completeLoad()
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)
        model.pause()
        scheduler.runCurrent()
        assertFalse(model.isPlaying.value)

        model.removeFromQueue(0)
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id, episodes[2].id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(episodes[1].id, model.currentEpisode.value?.id)
        assertEquals(FakePodcastAudioEngine.Load(episodes[1].id, 0, false), engine.loads.last())
        assertEquals(2, engine.loads.size)
        assertFalse(model.isPlaying.value)

        engine.completeLoad()
        scheduler.runCurrent()

        assertFalse(model.isLoading.value)
        assertFalse(model.isPlaying.value)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun removingTheCurrentEpisodeWhilePlayingLoadsItsSuccessorPlaying() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = playing(episodes)
        assertTrue(model.isPlaying.value)

        model.removeFromQueue(0)
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id, episodes[2].id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(episodes[1].id, model.currentEpisode.value?.id)
        assertEquals(FakePodcastAudioEngine.Load(episodes[1].id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)

        engine.completeLoad()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(0, engine.pauseCalls)
    }

    @Test
    fun removingTheCurrentEpisodeDuringALoadToPlayLoadsItsSuccessorPlaying() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = loading(episodes)
        assertTrue(model.isLoading.value)
        assertFalse(model.isPlaying.value)

        model.removeFromQueue(0)
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id, episodes[2].id), queueIds(model))
        assertEquals(episodes[1].id, model.currentEpisode.value?.id)
        assertEquals(FakePodcastAudioEngine.Load(episodes[1].id, 0, true), engine.loads.last())
        assertEquals(2, engine.loads.size)

        engine.completeLoad()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(0, engine.pauseCalls)
    }

    @Test
    fun removingTheCurrentEpisodeDuringALoadPausedByTheUserLoadsItsSuccessorPaused() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = loading(episodes)
        model.pause()
        scheduler.runCurrent()
        assertTrue(model.isLoading.value)

        model.removeFromQueue(0)
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id, episodes[2].id), queueIds(model))
        assertEquals(FakePodcastAudioEngine.Load(episodes[1].id, 0, false), engine.loads.last())
        assertEquals(2, engine.loads.size)

        engine.completeLoad()
        scheduler.runCurrent()

        assertFalse(model.isPlaying.value)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun removingTheOnlyEpisodeUnloadsThePlayer() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        moveEngineTo(12_000)

        model.removeFromQueue(0)
        scheduler.runCurrent()

        assertTrue(model.queue.value.isEmpty())
        assertEquals(-1, model.currentIndex.value)
        assertNull(model.currentEpisode.value)
        assertEquals(1, engine.stopCalls)
        assertFalse(model.isPlaying.value)
        assertEquals(0L, model.position.value)
        assertFalse(model.hasContent.value)
    }

    @Test
    fun moveInQueueKeepsTheCurrentEpisodeCurrent() {
        val episodes = List(5) { testEpisode(it) }
        val model = createModel()

        fun indexAfterMove(from: Int, to: Int): Int {
            model.playEpisodes(episodes, 2)
            model.moveInQueue(from, to)
            scheduler.runCurrent()
            assertEquals(episodes[2].id, model.queue.value[model.currentIndex.value].id)
            assertEquals(episodes[from].id, model.queue.value[to].id)
            return model.currentIndex.value
        }

        assertEquals(1, indexAfterMove(0, 3))
        assertEquals(1, indexAfterMove(1, 2))
        assertEquals(3, indexAfterMove(4, 1))
        assertEquals(3, indexAfterMove(3, 2))
        assertEquals(4, indexAfterMove(2, 4))
        assertEquals(0, indexAfterMove(2, 0))
        assertEquals(2, indexAfterMove(3, 4))
        assertEquals(2, indexAfterMove(0, 1))
    }

    @Test
    fun playNextMovesAnEarlierQueuedEpisodeBehindTheCurrentOne() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = playing(episodes, startIndex = 2)

        model.playNext(episodes[0])
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id, episodes[2].id, episodes[0].id), queueIds(model))
        assertEquals(1, model.currentIndex.value)
        assertEquals(episodes[2].id, model.currentEpisode.value?.id)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun addToQueueOnAnEmptyQueueStartsPlayingAndIgnoresDuplicates() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val third = testEpisode(3)
        val model = createModel()

        model.addToQueue(listOf(first, second, first))
        scheduler.runCurrent()

        assertEquals(listOf(first.id, second.id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(first.id, 0, true)), engine.loads)
        assertTrue(model.hasContent.value)

        model.addToQueue(listOf(second, third))
        model.addToQueue(listOf(first))
        scheduler.runCurrent()

        assertEquals(listOf(first.id, second.id, third.id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun playShowReportsAFailureOfTheService() {
        coEvery { podcastService.getLastPlayed(any()) } throws IllegalStateException("unreachable")
        val model = createModel()

        model.playShow(randomPlatformUUID())
        scheduler.runCurrent()

        assertEquals(1, errors.size)
        assertIs<PodcastPlayerError.Failed>(errors.single())
        assertTrue(model.queue.value.isEmpty())
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun aSecondPlayShowCancelsTheFirst() {
        val firstShow = randomPlatformUUID()
        val secondShow = randomPlatformUUID()
        val firstEpisode = testEpisode(1, showId = firstShow)
        val secondEpisode = testEpisode(2, showId = secondShow)
        val gate = CompletableDeferred<Unit>()
        var lookups = 0
        coEvery { podcastService.getLastPlayed(any()) } coAnswers {
            lookups++
            if (lookups == 1) {
                gate.await()
                firstEpisode
            } else {
                secondEpisode
            }
        }
        coEvery { podcastService.getEpisodeWindow(firstEpisode.id, any(), any()) } returns listOf(firstEpisode)
        coEvery { podcastService.getEpisodeWindow(secondEpisode.id, any(), any()) } returns listOf(secondEpisode)
        val model = createModel()

        model.playShow(firstShow)
        scheduler.runCurrent()
        model.playShow(secondShow)
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(2, lookups)
        assertEquals(listOf(secondEpisode.id), queueIds(model))
        assertEquals(listOf(FakePodcastAudioEngine.Load(secondEpisode.id, 0, true)), engine.loads)
        assertTrue(errors.isEmpty())
        coVerify(exactly = 0) { podcastService.getEpisodeWindow(firstEpisode.id, any(), any()) }
    }

    @Test
    fun markPlayedRecordsTheProgressAndPatchesTheQueue() {
        val episode = testEpisode(1)
        val played = testProgress(episode, positionMs = HOUR_MS, updatedAt = 500, completed = true)
        coEvery { podcastService.setPlayed(episode.id, true) } returns played
        val model = playing(listOf(episode, testEpisode(2)))

        model.markPlayed(episode, true)
        scheduler.runCurrent()

        assertEquals(played, store.latest.value[episode.id])
        assertEquals(played, model.queue.value[0].progress)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun markPlayedReportsAFailureOfTheService() {
        val episode = testEpisode(1)
        coEvery { podcastService.setPlayed(episode.id, true) } throws IllegalStateException("unreachable")
        val model = playing(listOf(episode))

        model.markPlayed(episode, true)
        scheduler.runCurrent()

        assertEquals(1, errors.size)
        assertIs<PodcastPlayerError.Failed>(errors.single())
        assertTrue(model.queue.value[0].progress?.completed != true)
    }

    @Test
    fun remoteProgressFromAnotherDeviceMovesThePausedPosition() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        model.pause()
        scheduler.runCurrent()

        store.record(testProgress(episode, positionMs = 500_000, updatedAt = 1_000_000, deviceId = OTHER_DEVICE_ID))
        scheduler.runCurrent()

        assertEquals(500_000L, model.position.value)
        assertTrue(engine.seeks.isEmpty())

        model.play()
        scheduler.runCurrent()

        assertEquals(listOf(500_000L), engine.seeks)
        assertEquals(1, engine.loads.size)
        assertTrue(model.isPlaying.value)
    }

    @Test
    fun remoteProgressFromThisDeviceDoesNotMoveThePausedPosition() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        moveEngineTo(20_000)
        model.pause()
        scheduler.runCurrent()

        store.record(testProgress(episode, positionMs = 500_000, updatedAt = 1_000_000, deviceId = DEVICE_ID))
        scheduler.runCurrent()

        assertEquals(20_000L, model.position.value)
        assertEquals(500_000L, model.queue.value[0].progress?.positionMs)
    }

    @Test
    fun remoteProgressDoesNotMoveThePositionWhilePlaying() {
        val episode = testEpisode(1)
        val model = createModel()
        model.playEpisode(episode)
        scheduler.runCurrent()
        engine.completeLoad()
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)
        moveEngineTo(20_000)

        store.record(testProgress(episode, positionMs = 500_000, updatedAt = 1_000_000, deviceId = OTHER_DEVICE_ID))
        scheduler.runCurrent()

        assertEquals(20_000L, model.position.value)
        assertTrue(engine.seeks.isEmpty())

        model.pause()
        scheduler.runCurrent()

        assertEquals(20_000L, model.position.value)
    }

    @Test
    fun restoreBringsBackTheQueuePausedAtTheSavedPosition() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val ids = episodes.map { it.id }
        writeState(PodcastPlayerState(ids, currentIndex = 1, positionMs = 77_000, savedAt = 5_000))
        coEvery { podcastService.getEpisodesByIds(ids) } returns episodes

        val model = createModel()

        assertEquals(ids, queueIds(model))
        assertEquals(1, model.currentIndex.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(ids[1], 77_000, false)), engine.loads)
        assertEquals(77_000L, model.position.value)
        assertFalse(model.isPlaying.value)
        assertTrue(reports.isEmpty())

        engine.completeLoad()
        scheduler.runCurrent()

        assertFalse(model.isLoading.value)
        assertFalse(model.isPlaying.value)
        assertEquals(77_000L, model.position.value)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun restoreUsesARemotePositionNewerThanTheSavedState() {
        val first = testEpisode(1)
        val bare = testEpisode(2)
        val second = bare.copy(progress = testProgress(bare, positionMs = 700_000, updatedAt = 6_000))
        val ids = listOf(first.id, second.id)
        writeState(PodcastPlayerState(ids, currentIndex = 1, positionMs = 77_000, savedAt = 5_000))
        coEvery { podcastService.getEpisodesByIds(ids) } returns listOf(first, second)

        createModel()

        assertEquals(listOf(FakePodcastAudioEngine.Load(second.id, 700_000, false)), engine.loads)
    }

    @Test
    fun restoreKeepsTheSavedPositionOverAnOlderRemotePosition() {
        val first = testEpisode(1)
        val bare = testEpisode(2)
        val second = bare.copy(progress = testProgress(bare, positionMs = 700_000, updatedAt = 4_000))
        val ids = listOf(first.id, second.id)
        writeState(PodcastPlayerState(ids, currentIndex = 1, positionMs = 77_000, savedAt = 5_000))
        coEvery { podcastService.getEpisodesByIds(ids) } returns listOf(first, second)

        createModel()

        assertEquals(listOf(FakePodcastAudioEngine.Load(second.id, 77_000, false)), engine.loads)
    }

    @Test
    fun restoreWithoutTheSavedCurrentEpisodeUsesTheClampedIndexAndItsOwnResume() {
        val first = testEpisode(1)
        val bare = testEpisode(2)
        val second = bare.copy(progress = testProgress(bare, positionMs = 123_000, updatedAt = 10))
        val vanished = testEpisode(3)
        val ids = listOf(first.id, second.id, vanished.id)
        writeState(PodcastPlayerState(ids, currentIndex = 2, positionMs = 77_000, savedAt = 5_000))
        coEvery { podcastService.getEpisodesByIds(ids) } returns listOf(first, second)

        val model = createModel()

        assertEquals(listOf(first.id, second.id), queueIds(model))
        assertEquals(1, model.currentIndex.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(second.id, 123_000, false)), engine.loads)
    }

    @Test
    fun restoreIsSkippedWhenTheUserQueuedFirst() {
        val saved = listOf(testEpisode(1), testEpisode(2))
        val ids = saved.map { it.id }
        val chosen = testEpisode(3)
        val gate = CompletableDeferred<Unit>()
        writeState(PodcastPlayerState(ids, currentIndex = 0, positionMs = 77_000, savedAt = 5_000))
        coEvery { rpcServiceManager.awaitAuthentication() } coAnswers { gate.await() }
        coEvery { podcastService.getEpisodesByIds(ids) } returns saved
        val model = createModel()
        assertTrue(model.queue.value.isEmpty())

        model.playEpisode(chosen)
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(listOf(chosen.id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(chosen.id, 0, true)), engine.loads)
    }

    @Test
    fun aCorruptStateFileLeavesAnEmptyQueue() {
        stateFile.writeBytes(ByteArray(64) { 0xFF.toByte() })

        val model = createModel()
        advance(2_000)

        assertTrue(model.queue.value.isEmpty())
        assertEquals(-1, model.currentIndex.value)
        assertTrue(engine.loads.isEmpty())
        assertTrue(errors.isEmpty())
        coVerify(exactly = 0) { podcastService.getEpisodesByIds(any()) }
    }

    @Test
    fun aQueueChangeIsWrittenToTheStateFileAfterOneSecond() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = createModel()

        model.playEpisodes(episodes, 1)
        advance(999)
        assertFalse(stateFile.exists())

        advance(1)

        assertTrue(stateFile.exists())
        val state = readState()
        assertEquals(episodes.map { it.id }, state.episodeIds)
        assertEquals(1, state.currentIndex)
    }

    @Test
    fun snapSpeedPicksTheNearestSupportedSpeed() {
        assertEquals(1.25f, PodcastPlayerModel.snapSpeed(1.3f))
        assertEquals(2f, PodcastPlayerModel.snapSpeed(5f))
        assertEquals(0.8f, PodcastPlayerModel.snapSpeed(0f))
    }

    @Test
    fun playEpisodesDropsDuplicatesAndStartsAtTheEpisodeOfTheStartIndex() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val third = testEpisode(3)
        val model = createModel()

        model.playEpisodes(listOf(first, second, first.copy(title = "Repeat"), third), 3)
        scheduler.runCurrent()

        assertEquals(listOf(first.id, second.id, third.id), queueIds(model))
        assertEquals(2, model.currentIndex.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(third.id, 0, true)), engine.loads)
    }

    @Test
    fun playEpisodesWithAnInvalidStartIndexStartsAtTheFirstEpisode() {
        val episodes = listOf(testEpisode(1), testEpisode(2))
        val model = createModel()

        model.playEpisodes(episodes, 7)
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(episodes[0].id, 0, true), engine.loads.last())

        model.playEpisodes(episodes, -1)
        scheduler.runCurrent()

        assertEquals(0, model.currentIndex.value)
        assertEquals(FakePodcastAudioEngine.Load(episodes[0].id, 0, true), engine.loads.last())
    }

    @Test
    fun playEpisodesWithAnEmptyListChangesNothing() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))

        model.playEpisodes(emptyList(), 0)
        scheduler.runCurrent()

        assertEquals(listOf(episode.id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(1, engine.loads.size)
        assertEquals(1, reports.size)
    }

    @Test
    fun switchingEpisodesReportsThePositionOfTheOutgoingEpisode() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        moveEngineTo(33_000)

        model.playAt(1)
        scheduler.runCurrent()

        assertEquals(listOf(first.id to 0L, first.id to 33_000L, second.id to 0L), sentPositions())
    }

    @Test
    fun togglePlayPausePausesWhilePlayingAndResumesWhilePaused() {
        val model = playing(listOf(testEpisode(1)))
        assertTrue(model.isPlaying.value)

        model.togglePlayPause()
        scheduler.runCurrent()

        assertFalse(model.isPlaying.value)
        assertEquals(1, engine.pauseCalls)
        assertEquals(0, engine.playCalls)

        model.togglePlayPause()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(1, engine.pauseCalls)
        assertEquals(1, engine.playCalls)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun togglePlayPauseDuringALoadLeavesTheEpisodePaused() {
        val episode = testEpisode(1)
        val model = loading(listOf(episode))
        assertTrue(model.isLoading.value)
        assertEquals(listOf(FakePodcastAudioEngine.Load(episode.id, 0, true)), engine.loads)

        model.togglePlayPause()
        scheduler.runCurrent()
        engine.completeLoad()
        scheduler.runCurrent()

        assertFalse(model.isPlaying.value)
        assertEquals(1, engine.pauseCalls)
        assertEquals(0, engine.playCalls)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun remoteProgressArrivingDuringALoadDoesNotReplaceTheShownPosition() {
        val episode = testEpisode(1)
        val model = loading(listOf(episode))
        assertTrue(model.isLoading.value)

        store.record(testProgress(episode, positionMs = 500_000, updatedAt = 1_000_000, deviceId = OTHER_DEVICE_ID))
        scheduler.runCurrent()
        engine.completeLoad()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(engine.position.value, model.position.value)

        moveEngineTo(20_000)

        assertEquals(20_000L, model.position.value)
    }

    @Test
    fun anEngineErrorIsForwardedAsTheSamePlayerError() {
        val model = loading(listOf(testEpisode(1)))

        engine.emitError(PodcastPlayerError.UnsupportedFormat)
        scheduler.runCurrent()

        assertEquals(listOf<PodcastPlayerError>(PodcastPlayerError.UnsupportedFormat), errors)
        assertFalse(model.isLoading.value)
        assertFalse(model.isPlaying.value)

        engine.emitError(PodcastPlayerError.Unavailable)
        engine.emitError(PodcastPlayerError.Failed("decoder"))
        scheduler.runCurrent()

        assertEquals(
            listOf(
                PodcastPlayerError.UnsupportedFormat,
                PodcastPlayerError.Unavailable,
                PodcastPlayerError.Failed("decoder")
            ),
            errors
        )
    }

    @Test
    fun aStreamErrorWhilePlayingReportsThePositionAndReloadsThereOnPlay() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        moveEngineTo(5 * MINUTE_MS)
        val reportsBefore = reports.size

        engine.emitError(PodcastPlayerError.Failed("stream"))
        scheduler.runCurrent()

        assertEquals(reportsBefore + 1, reports.size)
        assertEquals(EpisodePlaybackReport(episode.id, 5 * MINUTE_MS, HOUR_MS, false, DEVICE_ID), reports.last())
        assertEquals(5 * MINUTE_MS, model.position.value)
        assertEquals(listOf<PodcastPlayerError>(PodcastPlayerError.Failed("stream")), errors)

        model.play()
        scheduler.runCurrent()

        assertEquals(FakePodcastAudioEngine.Load(episode.id, 5 * MINUTE_MS, true), engine.loads.last())
        assertEquals(2, engine.loads.size)
    }

    @Test
    fun aStreamErrorWhilePausedReportsNoPosition() {
        val episode = testEpisode(1)
        val model = playing(listOf(episode))
        moveEngineTo(5 * MINUTE_MS)
        model.pause()
        scheduler.runCurrent()
        val reportsBefore = reports.size

        engine.emitError(PodcastPlayerError.Failed("stream"))
        scheduler.runCurrent()

        assertEquals(reportsBefore, reports.size)
        assertEquals(5 * MINUTE_MS, model.position.value)
    }

    @Test
    fun playAfterALoadErrorReloadsTheEpisode() {
        val episode = testEpisode(1)
        store.record(testProgress(episode, positionMs = 90_000, updatedAt = 100))
        val model = createModel()
        model.playEpisode(episode)
        scheduler.runCurrent()

        engine.emitError(PodcastPlayerError.Unavailable)
        scheduler.runCurrent()
        assertFalse(model.isPlaying.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(
            listOf(
                FakePodcastAudioEngine.Load(episode.id, 90_000, true),
                FakePodcastAudioEngine.Load(episode.id, 90_000, true)
            ),
            engine.loads
        )
        assertEquals(0, engine.playCalls)

        engine.completeLoad()
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
    }

    @Test
    fun setSpeedSnapsAndReachesTheEngineAndTheConfig() {
        val model = createModel()

        model.setSpeed(1.3f)
        scheduler.runCurrent()

        assertEquals(1.25f, model.speed.value)
        assertEquals(1.25f, engine.speeds.last())
        assertEquals(setOf(1f, 1.25f), engine.speeds.toSet())
        assertEquals(1.25f, Config.podcastPlaybackSpeed.value)
    }

    @Test
    fun setVolumeClampsAndTheVolumeOfThePlayerReachesTheEngine() {
        val model = createModel()
        assertEquals(listOf(1f), engine.volumes)

        model.setVolume(1.7f)
        model.setVolume(-0.5f)
        model.setVolume(0.4f)
        scheduler.runCurrent()

        verifyOrder {
            playerModel.setVolume(1f)
            playerModel.setVolume(0f)
            playerModel.setVolume(0.4f)
        }
        verify(exactly = 3) { playerModel.setVolume(any()) }

        volume.value = 0.4f
        scheduler.runCurrent()

        assertEquals(listOf(1f, 0.4f), engine.volumes)
        assertEquals(0.4f, model.volume.value)
    }

    @Test
    fun clearQueueKeepsOnlyTheCurrentEpisodePlaying() {
        val episodes = listOf(testEpisode(1), testEpisode(2), testEpisode(3))
        val model = playing(episodes, startIndex = 1)
        moveEngineTo(12_000)

        model.clearQueue()
        scheduler.runCurrent()

        assertEquals(listOf(episodes[1].id), queueIds(model))
        assertEquals(0, model.currentIndex.value)
        assertEquals(episodes[1].id, model.currentEpisode.value?.id)
        assertEquals(1, engine.loads.size)
        assertEquals(0, engine.stopCalls)
        assertTrue(model.isPlaying.value)
        assertEquals(12_000L, model.position.value)
        assertTrue(model.hasContent.value)
    }

    @Test
    fun skipNextPlaysTheNextEpisodeAndDoesNothingOnTheLastOne() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val model = playing(listOf(first, second))
        moveEngineTo(33_000)

        model.skipNext()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(
            listOf(FakePodcastAudioEngine.Load(first.id, 0, true), FakePodcastAudioEngine.Load(second.id, 0, true)),
            engine.loads
        )
        assertEquals(listOf(first.id to 0L, first.id to 33_000L, second.id to 0L), sentPositions())

        engine.completeLoad()
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)

        model.skipNext()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertEquals(2, engine.loads.size)
        assertTrue(engine.seeks.isEmpty())
        assertTrue(model.isPlaying.value)
        assertEquals(3, reports.size)
    }

    @Test
    fun skipBackSeeksBackByTheSkipDistanceAndStopsAtTheStart() {
        val episode = testEpisode(1, durationMs = HOUR_MS)
        val model = playing(listOf(episode))
        moveEngineTo(MINUTE_MS)

        model.skipBack()
        scheduler.runCurrent()

        assertEquals(listOf(MINUTE_MS - PodcastPlayer.SKIP_BACK_MS), engine.seeks)
        assertEquals(MINUTE_MS - PodcastPlayer.SKIP_BACK_MS, model.position.value)

        moveEngineTo(PodcastPlayer.SKIP_BACK_MS - 1)
        model.skipBack()
        scheduler.runCurrent()

        assertEquals(listOf(MINUTE_MS - PodcastPlayer.SKIP_BACK_MS, 0L), engine.seeks)
        assertEquals(0L, model.position.value)
        assertEquals(1, engine.loads.size)
    }

    @Test
    fun skipForwardSeeksForwardByTheSkipDistanceAndStopsAtTheDuration() {
        val episode = testEpisode(1, durationMs = HOUR_MS)
        val model = playing(listOf(episode))
        moveEngineTo(MINUTE_MS)

        model.skipForward()
        scheduler.runCurrent()

        assertEquals(listOf(MINUTE_MS + PodcastPlayer.SKIP_FORWARD_MS), engine.seeks)
        assertEquals(MINUTE_MS + PodcastPlayer.SKIP_FORWARD_MS, model.position.value)

        moveEngineTo(HOUR_MS - PodcastPlayer.SKIP_FORWARD_MS + 1)
        model.skipForward()
        scheduler.runCurrent()

        assertEquals(listOf(MINUTE_MS + PodcastPlayer.SKIP_FORWARD_MS, HOUR_MS), engine.seeks)
        assertEquals(HOUR_MS, model.position.value)
        assertEquals(1, engine.loads.size)
    }
}
