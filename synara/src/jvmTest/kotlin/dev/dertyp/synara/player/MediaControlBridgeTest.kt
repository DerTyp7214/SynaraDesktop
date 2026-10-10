package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.player.FakePodcastPlayer.Call
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControlBridgeTest {
    private val activePlayer = MutableStateFlow(ActivePlayer.SONGS)
    private val currentSong = MutableStateFlow<UserSong?>(null)
    private val songPlaying = MutableStateFlow(false)
    private val songPosition = MutableStateFlow(0L)
    private val songVolume = MutableStateFlow(1f)
    private val songRepeatMode = MutableStateFlow(RepeatMode.OFF)
    private val songShuffle = MutableStateFlow(false)
    private val podcast = FakePodcastPlayer()
    private lateinit var playerModel: PlayerModel
    private lateinit var playerSwitcher: PlayerSwitcher

    @BeforeTest
    fun setUp() {
        startTestKoin()
        playerModel = mockk(relaxed = true)
        every { playerModel.currentSong } returns currentSong
        every { playerModel.isPlaying } returns songPlaying
        every { playerModel.currentPosition } returns songPosition
        every { playerModel.volume } returns songVolume
        every { playerModel.repeatMode } returns songRepeatMode
        every { playerModel.shuffleMode } returns songShuffle
        playerSwitcher = mockk()
        every { playerSwitcher.active } returns activePlayer
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun TestScope.createBridge(): MediaControlBridge {
        val bridge = MediaControlBridge(
            playerModel,
            podcast,
            playerSwitcher,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return bridge
    }

    private fun episode(
        durationMs: Long? = null,
        imageId: PlatformUUID? = null,
        showImageId: PlatformUUID? = null
    ) = PodcastEpisode(
        id = randomPlatformUUID(),
        showId = randomPlatformUUID(),
        showTitle = "Show",
        guid = "guid",
        title = "Episode",
        publishedAt = 0L,
        durationMs = durationMs,
        imageId = imageId,
        showImageId = showImageId,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun transportCallsReachThePlayerModelWhileSongsAreActive() = runTest {
        val bridge = createBridge()

        bridge.play()
        bridge.pause()
        bridge.togglePlayPause()
        bridge.stop()
        bridge.next()
        bridge.previous()
        bridge.seekTo(4_200)

        verify(exactly = 1) { playerModel.play() }
        verify(exactly = 1) { playerModel.pause() }
        verify(exactly = 1) { playerModel.togglePlayPause() }
        verify(exactly = 1) { playerModel.stop() }
        verify(exactly = 1) { playerModel.skipNext() }
        verify(exactly = 1) { playerModel.skipPrevious() }
        verify(exactly = 1) { playerModel.seekTo(4_200) }
        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun transportCallsReachThePodcastPlayerWhileThePodcastIsActive() = runTest {
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        bridge.play()
        bridge.pause()
        bridge.togglePlayPause()
        bridge.stop()
        bridge.next()
        bridge.previous()
        bridge.seekTo(4_200)

        assertEquals(
            listOf(
                Call.Play,
                Call.Pause,
                Call.TogglePlayPause,
                Call.Stop,
                Call.SkipForward,
                Call.SkipBack,
                Call.SeekTo(4_200)
            ),
            podcast.calls
        )
        verify(exactly = 0) { playerModel.play() }
        verify(exactly = 0) { playerModel.pause() }
        verify(exactly = 0) { playerModel.togglePlayPause() }
        verify(exactly = 0) { playerModel.stop() }
        verify(exactly = 0) { playerModel.skipNext() }
        verify(exactly = 0) { playerModel.skipPrevious() }
        verify(exactly = 0) { playerModel.seekTo(any()) }
    }

    @Test
    fun seekByAddsTheOffsetToTheSongPosition() = runTest {
        songPosition.value = 10_000
        podcast.positionMs.value = 90_000
        val bridge = createBridge()

        bridge.seekBy(5_000)

        verify(exactly = 1) { playerModel.seekTo(15_000) }
        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun seekByAddsTheOffsetToThePodcastPosition() = runTest {
        songPosition.value = 10_000
        podcast.positionMs.value = 90_000
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        bridge.seekBy(5_000)

        assertEquals(listOf<Call>(Call.SeekTo(95_000)), podcast.calls)
        verify(exactly = 0) { playerModel.seekTo(any()) }
    }

    @Test
    fun setRateSnapsToTheNearestPodcastSpeed() = runTest {
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        bridge.setRate(1.3)

        assertEquals(listOf<Call>(Call.SetSpeed(1.25f)), podcast.calls)
    }

    @Test
    fun setRateDoesNothingWhileSongsAreActive() = runTest {
        val bridge = createBridge()

        bridge.setRate(1.3)

        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun setShuffleTogglesOnlyWhenTheValueDiffers() = runTest {
        val bridge = createBridge()

        bridge.setShuffle(false)
        verify(exactly = 0) { playerModel.toggleShuffle() }

        bridge.setShuffle(true)
        verify(exactly = 1) { playerModel.toggleShuffle() }

        songShuffle.value = true
        bridge.setShuffle(true)
        verify(exactly = 1) { playerModel.toggleShuffle() }

        bridge.setShuffle(false)
        verify(exactly = 2) { playerModel.toggleShuffle() }
    }

    @Test
    fun setRepeatModeReachesThePlayerModelWhileSongsAreActive() = runTest {
        val bridge = createBridge()

        bridge.setRepeatMode(RepeatMode.ONE)

        verify(exactly = 1) { playerModel.setRepeatMode(RepeatMode.ONE) }
    }

    @Test
    fun repeatAndShuffleAreIgnoredWhileThePodcastIsActive() = runTest {
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        bridge.setRepeatMode(RepeatMode.ALL)
        bridge.setShuffle(true)

        verify(exactly = 0) { playerModel.setRepeatMode(any()) }
        verify(exactly = 0) { playerModel.toggleShuffle() }
        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun isPlayingAndPositionFollowTheActivePlayer() = runTest {
        songPlaying.value = true
        songPosition.value = 1_000
        podcast.playing.value = false
        podcast.positionMs.value = 2_000
        val bridge = createBridge()
        assertTrue(bridge.isPlaying.value)
        assertEquals(1_000L, bridge.positionFlow.value)
        assertEquals(1_000L, bridge.position())

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertFalse(bridge.isPlaying.value)
        assertEquals(2_000L, bridge.positionFlow.value)
        assertEquals(2_000L, bridge.position())

        podcast.playing.value = true
        podcast.positionMs.value = 3_000
        runCurrent()
        assertTrue(bridge.isPlaying.value)
        assertEquals(3_000L, bridge.positionFlow.value)

        songPlaying.value = false
        activePlayer.value = ActivePlayer.SONGS
        runCurrent()
        assertFalse(bridge.isPlaying.value)
        assertEquals(1_000L, bridge.positionFlow.value)
    }

    @Test
    fun rateFollowsThePodcastSpeedOnlyWhileThePodcastIsActive() = runTest {
        podcast.playbackSpeed.value = 1.5f
        val bridge = createBridge()
        assertEquals(1.0, bridge.rate.value)
        assertEquals(1.0, bridge.minimumRate)
        assertEquals(1.0, bridge.maximumRate)

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertEquals(1.5, bridge.rate.value)
        assertEquals(MediaControlBridge.PODCAST_MIN_RATE, bridge.minimumRate)
        assertEquals(MediaControlBridge.PODCAST_MAX_RATE, bridge.maximumRate)

        podcast.playbackSpeed.value = 2f
        runCurrent()
        assertEquals(2.0, bridge.rate.value)

        activePlayer.value = ActivePlayer.SONGS
        runCurrent()
        assertEquals(1.0, bridge.rate.value)
    }

    @Test
    fun isPodcastModeFollowsTheActivePlayer() = runTest {
        val bridge = createBridge()
        assertFalse(bridge.isPodcastMode.value)

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertTrue(bridge.isPodcastMode.value)

        activePlayer.value = ActivePlayer.SONGS
        runCurrent()
        assertFalse(bridge.isPodcastMode.value)
    }

    @Test
    fun repeatIsOffAndShuffleIsFalseWhileThePodcastIsActive() = runTest {
        songRepeatMode.value = RepeatMode.ALL
        songShuffle.value = true
        val bridge = createBridge()
        assertEquals(RepeatMode.ALL, bridge.repeatMode.value)
        assertTrue(bridge.shuffleMode.value)

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertEquals(RepeatMode.OFF, bridge.repeatMode.value)
        assertFalse(bridge.shuffleMode.value)

        activePlayer.value = ActivePlayer.SONGS
        runCurrent()
        assertEquals(RepeatMode.ALL, bridge.repeatMode.value)
        assertTrue(bridge.shuffleMode.value)
    }

    @Test
    fun episodeMetadataTakesTheDurationFromThePlayer() = runTest {
        val artwork = randomPlatformUUID()
        val playing = episode(durationMs = 60_000, imageId = artwork, showImageId = randomPlatformUUID())
        podcast.episode.value = playing
        podcast.durationMs.value = 61_000
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        val metadata = assertNotNull(bridge.metadata.value)
        assertTrue(metadata.isPodcast)
        assertEquals(playing.id.toString(), metadata.trackId)
        assertEquals(61_000L, metadata.durationMs)
        assertEquals(artwork, metadata.imageId)
    }

    @Test
    fun episodeMetadataFallsBackToTheKnownDurationWhenThePlayerHasNone() = runTest {
        val showArtwork = randomPlatformUUID()
        podcast.episode.value = episode(durationMs = 60_000, showImageId = showArtwork)
        podcast.durationMs.value = 0
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        val metadata = assertNotNull(bridge.metadata.value)
        assertTrue(metadata.isPodcast)
        assertEquals(60_000L, metadata.durationMs)
        assertEquals(showArtwork, metadata.imageId)
    }

    @Test
    fun episodeMetadataHasZeroDurationWhenNoDurationIsKnown() = runTest {
        podcast.episode.value = episode()
        podcast.durationMs.value = 0
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()

        val metadata = assertNotNull(bridge.metadata.value)
        assertEquals(0L, metadata.durationMs)
        assertNull(metadata.imageId)
    }

    @Test
    fun episodeMetadataFollowsThePlayerDuration() = runTest {
        podcast.episode.value = episode(durationMs = 60_000)
        activePlayer.value = ActivePlayer.PODCAST
        val bridge = createBridge()
        assertEquals(60_000L, bridge.metadata.value?.durationMs)

        podcast.durationMs.value = 61_000
        runCurrent()

        assertEquals(61_000L, bridge.metadata.value?.durationMs)
    }

    @Test
    fun songMetadataCarriesTheTrackAndCoverIds() = runTest {
        val coverId = randomPlatformUUID()
        val song = testSong(1, duration = 185_000, album = testAlbum(1), artists = listOf(testArtist(1), testArtist(2)))
            .copy(coverId = coverId)
        val bridge = createBridge()

        currentSong.value = song
        runCurrent()

        val metadata = assertNotNull(bridge.metadata.value)
        assertFalse(metadata.isPodcast)
        assertEquals(song.id.toString(), metadata.trackId)
        assertEquals(coverId, metadata.imageId)
        assertEquals(185_000L, metadata.durationMs)
        assertEquals(2, metadata.artists.size)
    }

    @Test
    fun metadataIsNullWhenNothingIsLoaded() = runTest {
        val bridge = createBridge()
        assertNull(bridge.metadata.value)

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertNull(bridge.metadata.value)
    }

    @Test
    fun metadataSwitchesBetweenTheSongAndTheEpisode() = runTest {
        val song = testSong(1)
        val playing = episode(durationMs = 60_000)
        currentSong.value = song
        podcast.episode.value = playing
        val bridge = createBridge()
        assertEquals(song.id.toString(), bridge.metadata.value?.trackId)

        activePlayer.value = ActivePlayer.PODCAST
        runCurrent()
        assertEquals(playing.id.toString(), bridge.metadata.value?.trackId)

        activePlayer.value = ActivePlayer.SONGS
        runCurrent()
        assertEquals(song.id.toString(), bridge.metadata.value?.trackId)
    }

    @Test
    fun podcastRateBoundsEqualTheSlowestAndFastestSpeed() {
        assertEquals(PodcastPlayer.SPEEDS.min(), MediaControlBridge.PODCAST_MIN_RATE.toFloat())
        assertEquals(PodcastPlayer.SPEEDS.max(), MediaControlBridge.PODCAST_MAX_RATE.toFloat())
    }
}
