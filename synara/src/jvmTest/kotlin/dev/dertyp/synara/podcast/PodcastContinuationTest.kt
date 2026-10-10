package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IPodcastService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastContinuationTest {

    private val showId: PlatformUUID = randomPlatformUUID()
    private val podcastService = mockk<IPodcastService>()

    private fun episode(index: Int, show: PlatformUUID = showId): PodcastEpisode = testEpisode(index, showId = show)

    private fun PodcastEpisode.inProgress(positionMs: Long = 60_000, lastPlayedAt: Long = 100): PodcastEpisode =
        copy(progress = testProgress(this, positionMs = positionMs, updatedAt = lastPlayedAt, lastPlayedAt = lastPlayedAt))

    private fun PodcastEpisode.played(lastPlayedAt: Long = 100): PodcastEpisode =
        copy(progress = testProgress(this, positionMs = 0, updatedAt = lastPlayedAt, completed = true, lastPlayedAt = lastPlayedAt))

    private fun stubNewest(episodes: List<PodcastEpisode>) {
        coEvery { podcastService.getEpisodes(showId, 0, 50, true) } returns PaginatedResponse(episodes)
    }

    private fun stubOldest(episodes: List<PodcastEpisode>) {
        coEvery { podcastService.getEpisodes(showId, 0, 1, false) } returns PaginatedResponse(episodes)
    }

    @Test
    fun anUnfinishedLastPlayedEpisodeOfTheShowIsContinued() = runTest {
        val lastPlayed = episode(1).inProgress()
        coEvery { podcastService.getLastPlayed(true) } returns lastPlayed

        assertEquals(PodcastContinuation.Continue(lastPlayed), podcastService.resolveContinuation(showId))
        coVerify(exactly = 0) { podcastService.getEpisodes(any(), any(), any(), any()) }
        coVerify(exactly = 0) { podcastService.getEpisodeWindow(any(), any(), any()) }
    }

    @Test
    fun aLastPlayedEpisodeThatWasNeverStartedIsContinued() = runTest {
        val lastPlayed = episode(1)
        coEvery { podcastService.getLastPlayed(true) } returns lastPlayed

        assertEquals(PodcastContinuation.Continue(lastPlayed), podcastService.resolveContinuation(showId))
    }

    @Test
    fun aCompletedLastPlayedEpisodeLeadsToTheNextNewerEpisode() = runTest {
        val lastPlayed = episode(1).played()
        val newer = episode(2)
        coEvery { podcastService.getLastPlayed(true) } returns lastPlayed
        coEvery { podcastService.getEpisodeWindow(lastPlayed.id, 0, 1) } returns listOf(lastPlayed, newer)

        assertEquals(PodcastContinuation.Next(newer), podcastService.resolveContinuation(showId))
    }

    @Test
    fun aNewerEpisodeThatIsInProgressIsContinued() = runTest {
        val lastPlayed = episode(1).played()
        val newer = episode(2).inProgress()
        coEvery { podcastService.getLastPlayed(true) } returns lastPlayed
        coEvery { podcastService.getEpisodeWindow(lastPlayed.id, 0, 1) } returns listOf(lastPlayed, newer)

        assertEquals(PodcastContinuation.Continue(newer), podcastService.resolveContinuation(showId))
    }

    @Test
    fun aCompletedLastPlayedEpisodeWithNothingNewerGivesNoContinuation() = runTest {
        val lastPlayed = episode(1).played()
        coEvery { podcastService.getLastPlayed(true) } returns lastPlayed
        coEvery { podcastService.getEpisodeWindow(lastPlayed.id, 0, 1) } returns listOf(lastPlayed)

        assertNull(podcastService.resolveContinuation(showId))
        coVerify(exactly = 0) { podcastService.getEpisodes(any(), any(), any(), any()) }
    }

    @Test
    fun aLastPlayedEpisodeOfAnotherShowFallsBackToTheMostRecentlyPlayedOfTheNewestFifty() = runTest {
        val elsewhere = episode(9, show = randomPlatformUUID()).inProgress(lastPlayedAt = 5_000)
        val untouched = episode(5)
        val resetToStart = episode(4).inProgress(positionMs = 0, lastPlayedAt = 900)
        val started = episode(3).inProgress(positionMs = 30_000, lastPlayedAt = 300)
        val finished = episode(2).played(lastPlayedAt = 200)
        val startedEarlier = episode(1).inProgress(positionMs = 45_000, lastPlayedAt = 100)
        coEvery { podcastService.getLastPlayed(true) } returns elsewhere
        stubNewest(listOf(untouched, resetToStart, started, finished, startedEarlier))

        assertEquals(PodcastContinuation.Continue(started), podcastService.resolveContinuation(showId))
        coVerify(exactly = 1) { podcastService.getEpisodes(showId, 0, 50, true) }
    }

    @Test
    fun aPlayedEpisodeFoundByTheScanLeadsToTheNextNewerEpisode() = runTest {
        val newer = episode(3)
        val finished = episode(2).played(lastPlayedAt = 400)
        val started = episode(1).inProgress(positionMs = 30_000, lastPlayedAt = 300)
        coEvery { podcastService.getLastPlayed(true) } returns null
        stubNewest(listOf(newer, finished, started))
        coEvery { podcastService.getEpisodeWindow(finished.id, 0, 1) } returns listOf(finished, newer)

        assertEquals(PodcastContinuation.Next(newer), podcastService.resolveContinuation(showId))
    }

    @Test
    fun aShowWithNothingPlayedStartsAtItsOldestEpisode() = runTest {
        val oldest = episode(1)
        val newest = episode(2)
        coEvery { podcastService.getLastPlayed(true) } returns null
        stubNewest(listOf(newest, oldest))
        stubOldest(listOf(oldest))

        assertEquals(PodcastContinuation.Start(oldest), podcastService.resolveContinuation(showId))
        coVerify(exactly = 1) { podcastService.getEpisodes(showId, 0, 1, false) }
    }

    @Test
    fun aShowWithoutEpisodesGivesNoContinuation() = runTest {
        coEvery { podcastService.getLastPlayed(true) } returns null
        stubNewest(emptyList())
        stubOldest(emptyList())

        assertNull(podcastService.resolveContinuation(showId))
    }

    @Test
    fun theContinuationQueueDropsEpisodesBeforeTheTarget() = runTest {
        val older = episode(1)
        val target = episode(2)
        val newer = listOf(episode(3), episode(4))
        coEvery { podcastService.getEpisodeWindow(target.id, 0, PODCAST_CONTINUATION_NEWER) } returns listOf(older, target) + newer

        assertEquals(listOf(target) + newer, podcastService.continuationQueue(target))
    }

    @Test
    fun anEmptyWindowGivesAQueueOfOnlyTheTarget() = runTest {
        val target = episode(2)
        coEvery { podcastService.getEpisodeWindow(target.id, 0, PODCAST_CONTINUATION_NEWER) } returns emptyList()

        assertEquals(listOf(target), podcastService.continuationQueue(target))
    }

    @Test
    fun aWindowWithoutTheTargetGivesAQueueOfOnlyTheTarget() = runTest {
        val target = episode(2)
        coEvery { podcastService.getEpisodeWindow(target.id, 0, PODCAST_CONTINUATION_NEWER) } returns listOf(episode(1), episode(3))

        assertEquals(listOf(target), podcastService.continuationQueue(target))
    }
}
