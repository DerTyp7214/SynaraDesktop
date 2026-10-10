package dev.dertyp.synara.player.audio.source

import dev.dertyp.data.PodcastEpisode
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IPodcastService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeAudioSourceTest {
    private val podcastService = mockk<IPodcastService>()
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newEpisode(
        imported: Boolean = false,
        durationMs: Long? = null,
        enclosureType: String? = null,
        enclosureLength: Long? = null,
        fileSize: Long? = null,
        format: String? = null
    ) = PodcastEpisode(
        id = randomPlatformUUID(),
        showId = randomPlatformUUID(),
        showTitle = "Show",
        guid = "guid",
        title = "Episode",
        publishedAt = 0,
        durationMs = durationMs,
        enclosureType = enclosureType,
        enclosureLength = enclosureLength,
        imported = imported,
        fileSize = fileSize,
        format = format,
        createdAt = 0,
        updatedAt = 0
    )

    @Test
    fun cacheKeyDistinguishesStoredFromRelayedEpisodes() {
        val stored = newEpisode(imported = true)
        val relayed = newEpisode(imported = false)

        assertEquals("episode:${stored.id}:stored", EpisodeAudioSource(stored, podcastService).cacheKey)
        assertEquals("episode:${relayed.id}:relay", EpisodeAudioSource(relayed, podcastService).cacheKey)
    }

    @Test
    fun sizeFromTheServerWins() = runBlocking {
        val episode = newEpisode(fileSize = 100, enclosureLength = 200)
        coEvery { podcastService.getStreamSize(episode.id) } returns 900L

        assertEquals(900L, EpisodeAudioSource(episode, podcastService).size())
    }

    @Test
    fun sizeFallsBackToTheFileSizeWhenTheServerKnowsNone() = runBlocking {
        val episode = newEpisode(fileSize = 100, enclosureLength = 200)
        coEvery { podcastService.getStreamSize(episode.id) } returns 0L

        assertEquals(100L, EpisodeAudioSource(episode, podcastService).size())
    }

    @Test
    fun sizeFallsBackToTheEnclosureLengthWithoutAFileSize() = runBlocking {
        val episode = newEpisode(enclosureLength = 200)
        coEvery { podcastService.getStreamSize(episode.id) } returns 0L

        assertEquals(200L, EpisodeAudioSource(episode, podcastService).size())
    }

    @Test
    fun sizeIsZeroWhenNothingIsKnown() = runBlocking {
        val episode = newEpisode()
        coEvery { podcastService.getStreamSize(episode.id) } returns 0L

        assertEquals(0L, EpisodeAudioSource(episode, podcastService).size())
    }

    @Test
    fun serviceFailureFallsBackToTheKnownSizes() = runBlocking {
        val episode = newEpisode(fileSize = 100, enclosureLength = 200)
        coEvery { podcastService.getStreamSize(episode.id) } throws IOException()

        assertEquals(100L, EpisodeAudioSource(episode, podcastService).size())
    }

    @Test
    fun cancellationFromTheServiceIsRethrown() = runBlocking {
        val episode = newEpisode(fileSize = 100)
        coEvery { podcastService.getStreamSize(episode.id) } throws CancellationException()

        assertFailsWith<CancellationException> { EpisodeAudioSource(episode, podcastService).size() }
        Unit
    }

    @Test
    fun resolvedSizeIsCachedAcrossCalls() = runBlocking {
        val episode = newEpisode()
        coEvery { podcastService.getStreamSize(episode.id) } returns 900L
        val source = EpisodeAudioSource(episode, podcastService)

        assertEquals(900L, source.size())
        assertEquals(900L, source.size())

        coVerify(exactly = 1) { podcastService.getStreamSize(episode.id) }
    }

    @Test
    fun fallbackSizeIsCachedToo() = runBlocking {
        val episode = newEpisode(fileSize = 100)
        coEvery { podcastService.getStreamSize(episode.id) } throws IOException()
        val source = EpisodeAudioSource(episode, podcastService)

        assertEquals(100L, source.size())
        assertEquals(100L, source.size())

        coVerify(exactly = 1) { podcastService.getStreamSize(episode.id) }
    }

    @Test
    fun formatHintPrefersTheStoredFormatOverTheEnclosureType() {
        assertEquals("flac", EpisodeAudioSource(newEpisode(format = "flac", enclosureType = "audio/mpeg"), podcastService).formatHint)
        assertEquals("audio/mpeg", EpisodeAudioSource(newEpisode(format = null, enclosureType = "audio/mpeg"), podcastService).formatHint)
        assertNull(EpisodeAudioSource(newEpisode(), podcastService).formatHint)
    }

    @Test
    fun durationHintIgnoresNonPositiveValues() {
        assertNull(EpisodeAudioSource(newEpisode(durationMs = null), podcastService).durationMsHint)
        assertNull(EpisodeAudioSource(newEpisode(durationMs = 0), podcastService).durationMsHint)
        assertNull(EpisodeAudioSource(newEpisode(durationMs = -5), podcastService).durationMsHint)
        assertEquals(1500L, EpisodeAudioSource(newEpisode(durationMs = 1500), podcastService).durationMsHint)
    }

    @Test
    fun bitRateHintIsUnknown() {
        assertNull(EpisodeAudioSource(newEpisode(), podcastService).bitRateHint)
    }

    @Test
    fun prepareSucceedsWithoutAnyLookup() = runBlocking {
        assertTrue(EpisodeAudioSource(newEpisode(), podcastService).prepare())
    }

    @Test
    fun openStreamsTheEpisodeFromTheOffset() = runBlocking {
        val episode = newEpisode()
        every { podcastService.streamEpisode(episode.id, 12L, any()) } returns flowOf(byteArrayOf(5, 6))

        val stream = assertNotNull(EpisodeAudioSource(episode, podcastService).open(12L, scope))

        assertEquals(listOf(5, 6, -1), List(3) { stream.read() })
        stream.close()
    }

    @Test
    fun openGivesNullWhenTheEpisodeHasNoAudio() = runBlocking {
        val episode = newEpisode()
        every { podcastService.streamEpisode(episode.id, 0L, any()) } returns null

        assertNull(EpisodeAudioSource(episode, podcastService).open(0L, scope))
    }
}
