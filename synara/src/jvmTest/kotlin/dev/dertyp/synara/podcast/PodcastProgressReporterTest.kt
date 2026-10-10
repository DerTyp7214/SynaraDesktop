package dev.dertyp.synara.podcast

import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.testing.TestDispatchers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastProgressReporterTest {

    private companion object {
        const val DEVICE_ID = "this-device"
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val podcastService = mockk<IPodcastService>()
    private val store = InMemoryProgressStore()
    private val sent = mutableListOf<EpisodePlaybackReport>()
    private val answered = mutableListOf<PodcastEpisodeProgress>()
    private val failingPositions = mutableSetOf<Long>()
    private var clock = 0L

    private lateinit var reporter: PodcastProgressReporter

    @BeforeTest
    fun setUp() {
        coEvery { podcastService.reportPlayback(any()) } answers {
            val report = firstArg<EpisodePlaybackReport>()
            sent += report
            answer(report)
        }
        reporter = PodcastProgressReporter(
            podcastService = podcastService,
            progressStore = store,
            deviceIdentity = mockk<DeviceIdentity> { every { deviceId } returns DEVICE_ID },
            logger = mockk<Logger>(relaxed = true),
            dispatchers = TestDispatchers(dispatcher)
        )
        scheduler.runCurrent()
    }

    private fun answer(report: EpisodePlaybackReport): PodcastEpisodeProgress {
        if (report.positionMs in failingPositions) throw IllegalStateException("report failed")
        clock++
        return PodcastEpisodeProgress(
            episodeId = report.episodeId,
            showId = randomPlatformUUID(),
            positionMs = report.positionMs,
            durationMs = report.durationMs,
            completed = report.completed,
            lastPlayedAt = clock,
            updatedAt = clock,
            deviceId = report.deviceId
        ).also { answered += it }
    }

    @Test
    fun aNegativePositionIsSentAsZeroAndANonPositiveDurationAsUnknown() {
        val episode = testEpisode(1)

        reporter.report(episode, positionMs = -5, durationMs = 0)
        reporter.report(episode, positionMs = 10, durationMs = -1)
        reporter.report(episode, positionMs = 20, durationMs = null)
        reporter.report(episode, positionMs = 30, durationMs = 500, completed = true)
        scheduler.runCurrent()

        assertEquals(
            listOf(
                EpisodePlaybackReport(episode.id, 0, null, false, DEVICE_ID),
                EpisodePlaybackReport(episode.id, 10, null, false, DEVICE_ID),
                EpisodePlaybackReport(episode.id, 20, null, false, DEVICE_ID),
                EpisodePlaybackReport(episode.id, 30, 500, true, DEVICE_ID)
            ),
            sent
        )
    }

    @Test
    fun reportsAreSentInCallOrderAndEverySuccessIsRecorded() {
        val first = testEpisode(1)
        val second = testEpisode(2)
        val firstAnswer = CompletableDeferred<Unit>()
        coEvery { podcastService.reportPlayback(any()) } coAnswers {
            val report = firstArg<EpisodePlaybackReport>()
            sent += report
            if (sent.size == 1) firstAnswer.await()
            answer(report)
        }

        reporter.report(first, positionMs = 1_000, durationMs = 60_000)
        reporter.report(second, positionMs = 2_000, durationMs = 60_000)
        reporter.report(first, positionMs = 3_000, durationMs = 60_000)
        scheduler.runCurrent()

        assertEquals(listOf(first.id to 1_000L), sent.map { it.episodeId to it.positionMs })
        assertTrue(store.recorded.isEmpty())

        firstAnswer.complete(Unit)
        scheduler.runCurrent()

        assertEquals(listOf(first.id to 1_000L, second.id to 2_000L, first.id to 3_000L), sent.map { it.episodeId to it.positionMs })
        assertEquals(3, answered.size)
        assertEquals(listOf(first.id to 1_000L, second.id to 2_000L, first.id to 3_000L), store.recorded.map { it.episodeId to it.positionMs })
        assertEquals(answered, store.recorded)
        assertEquals(answered[2], store.latest.value[first.id])
        assertEquals(answered[1], store.latest.value[second.id])
    }

    @Test
    fun aFailedReportDoesNotStopLaterReports() {
        val episode = testEpisode(1)
        failingPositions += 1_000L

        reporter.report(episode, positionMs = 1_000, durationMs = 60_000)
        scheduler.runCurrent()
        reporter.report(episode, positionMs = 2_000, durationMs = 60_000)
        reporter.report(episode, positionMs = 3_000, durationMs = 60_000)
        scheduler.runCurrent()

        assertEquals(listOf(1_000L, 2_000L, 3_000L), sent.map { it.positionMs })
        assertEquals(listOf(2_000L, 3_000L), store.recorded.map { it.positionMs })
    }
}
