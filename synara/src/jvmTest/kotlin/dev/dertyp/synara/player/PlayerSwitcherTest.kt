package dev.dertyp.synara.player

import dev.dertyp.data.UserSong
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.FakePodcastPlayer.Call
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSwitcherTest {
    private val currentSong = MutableStateFlow<UserSong?>(null)
    private val songPlaying = MutableStateFlow(false)
    private val songFft = MutableStateFlow(FloatArray(0))
    private val songStereoFft = MutableStateFlow(StereoSpectrum.EMPTY)
    private val songIntensity = MutableStateFlow(0f)
    private val songSampleRate = MutableStateFlow(0)
    private var songPlaybackRequested = false
    private val podcast = FakePodcastPlayer()
    private lateinit var playerModel: PlayerModel

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setPodcastsEnabled(true)
        playerModel = mockk(relaxed = true)
        every { playerModel.currentSong } returns currentSong
        every { playerModel.isPlaying } returns songPlaying
        every { playerModel.fftData } returns songFft
        every { playerModel.stereoFftData } returns songStereoFft
        every { playerModel.audioIntensity } returns songIntensity
        every { playerModel.sampleRate } returns songSampleRate
        every { playerModel.playbackRequested } answers { songPlaybackRequested }
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun TestScope.createSwitcher(): PlayerSwitcher {
        val switcher = PlayerSwitcher(playerModel, podcast, TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        return switcher
    }

    private fun TestScope.startPodcast(switcher: PlayerSwitcher) {
        podcast.content.value = true
        podcast.playing.value = true
        runCurrent()
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun songStartingWhileThePodcastPlaysPausesThePodcastAndActivatesSongs() = runTest {
        val switcher = createSwitcher()
        startPodcast(switcher)

        songPlaying.value = true
        runCurrent()

        assertEquals(listOf<Call>(Call.Pause), podcast.calls)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    @Test
    fun podcastStartingWhileSongPlaybackIsRequestedPausesSongsAndActivatesThePodcast() = runTest {
        val switcher = createSwitcher()
        songPlaybackRequested = true

        podcast.content.value = true
        podcast.playing.value = true
        runCurrent()

        verify(exactly = 1) { playerModel.pause() }
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun podcastStartingWithoutRequestedSongPlaybackDoesNotPauseSongs() = runTest {
        val switcher = createSwitcher()
        songPlaybackRequested = false

        podcast.content.value = true
        podcast.playing.value = true
        runCurrent()

        verify(exactly = 0) { playerModel.pause() }
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun podcastLosingItsContentFallsBackToSongs() = runTest {
        val switcher = createSwitcher()
        startPodcast(switcher)

        podcast.content.value = false
        runCurrent()

        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    @Test
    fun disablingPodcastsStopsThePodcastAndActivatesSongs() = runTest {
        val switcher = createSwitcher()
        startPodcast(switcher)

        Config.setPodcastsEnabled(false)
        runCurrent()

        assertEquals(listOf<Call>(Call.Stop), podcast.calls)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    @Test
    fun selectingThePodcastWithoutContentIsIgnored() = runTest {
        val switcher = createSwitcher()
        songPlaybackRequested = true

        switcher.select(ActivePlayer.PODCAST)
        runCurrent()

        assertEquals(ActivePlayer.SONGS, switcher.active.value)
        verify(exactly = 0) { playerModel.pause() }
        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun selectingThePodcastWithContentPausesRequestedSongPlayback() = runTest {
        val switcher = createSwitcher()
        podcast.content.value = true
        songPlaybackRequested = true

        switcher.select(ActivePlayer.PODCAST)
        runCurrent()

        verify(exactly = 1) { playerModel.pause() }
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun selectingThePodcastWithContentLeavesSongsAloneWhenNoPlaybackIsRequested() = runTest {
        val switcher = createSwitcher()
        podcast.content.value = true
        songPlaybackRequested = false

        switcher.select(ActivePlayer.PODCAST)
        runCurrent()

        verify(exactly = 0) { playerModel.pause() }
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun selectingSongsPausesALoadingPodcast() = runTest {
        val switcher = createSwitcher()
        podcast.content.value = true
        switcher.select(ActivePlayer.PODCAST)
        podcast.loading.value = true
        runCurrent()

        switcher.select(ActivePlayer.SONGS)
        runCurrent()

        assertEquals(listOf<Call>(Call.Pause), podcast.calls)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    @Test
    fun selectingTheActivePlayerMakesNoCalls() = runTest {
        val switcher = createSwitcher()
        podcast.content.value = true
        podcast.loading.value = true
        runCurrent()

        switcher.select(ActivePlayer.SONGS)
        runCurrent()
        assertEquals(emptyList<Call>(), podcast.calls)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)

        podcast.loading.value = false
        switcher.select(ActivePlayer.PODCAST)
        songPlaybackRequested = true
        switcher.select(ActivePlayer.PODCAST)
        runCurrent()

        verify(exactly = 0) { playerModel.pause() }
        assertEquals(emptyList<Call>(), podcast.calls)
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun isPlayingFollowsTheActivePlayer() = runTest {
        val switcher = createSwitcher()
        songPlaying.value = true
        runCurrent()
        assertTrue(switcher.isPlaying.value)

        podcast.content.value = true
        switcher.select(ActivePlayer.PODCAST)
        runCurrent()
        assertFalse(switcher.isPlaying.value)

        songPlaying.value = false
        podcast.playing.value = true
        runCurrent()
        assertTrue(switcher.isPlaying.value)

        switcher.select(ActivePlayer.SONGS)
        runCurrent()
        assertFalse(switcher.isPlaying.value)
    }

    @Test
    fun fftDataFollowsTheActivePlayer() = runTest {
        val songSpectrum = floatArrayOf(0.1f, 0.2f)
        val podcastSpectrum = floatArrayOf(0.7f, 0.8f)
        songFft.value = songSpectrum
        podcast.fft.value = podcastSpectrum
        podcast.content.value = true
        val switcher = createSwitcher()
        assertSame(songSpectrum, switcher.fftData.value)

        switcher.select(ActivePlayer.PODCAST)
        runCurrent()
        assertSame(podcastSpectrum, switcher.fftData.value)

        switcher.select(ActivePlayer.SONGS)
        runCurrent()
        assertSame(songSpectrum, switcher.fftData.value)
    }

    @Test
    fun sampleRateFollowsTheActivePlayer() = runTest {
        songSampleRate.value = 44_100
        podcast.rate.value = 48_000
        podcast.content.value = true
        val switcher = createSwitcher()
        assertEquals(44_100, switcher.sampleRate.value)

        switcher.select(ActivePlayer.PODCAST)
        runCurrent()
        assertEquals(48_000, switcher.sampleRate.value)

        switcher.select(ActivePlayer.SONGS)
        runCurrent()
        assertEquals(44_100, switcher.sampleRate.value)
    }

    @Test
    fun bothAvailableNeedsACurrentSongAndPodcastContent() = runTest {
        val switcher = createSwitcher()
        assertFalse(switcher.bothAvailable.value)

        currentSong.value = testSong(1)
        runCurrent()
        assertFalse(switcher.bothAvailable.value)

        podcast.content.value = true
        runCurrent()
        assertTrue(switcher.bothAvailable.value)

        currentSong.value = null
        runCurrent()
        assertFalse(switcher.bothAvailable.value)

        currentSong.value = testSong(2)
        runCurrent()
        assertTrue(switcher.bothAvailable.value)

        podcast.content.value = false
        runCurrent()
        assertFalse(switcher.bothAvailable.value)
    }
}
