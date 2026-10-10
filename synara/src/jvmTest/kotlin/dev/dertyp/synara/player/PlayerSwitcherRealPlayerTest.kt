package dev.dertyp.synara.player

import dev.dertyp.logging.Logger
import dev.dertyp.serializers.AppCbor
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.FakePodcastPlayer.Call
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.testing.*
import dev.dertyp.synara.ui.models.SnackbarManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSwitcherRealPlayerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val audioPlayer = FakeAudioPlayer()
    private val podcast = FakePodcastPlayer()
    private val songService = mockk<SongServiceWrapper>(relaxed = true)
    private val stateDir: File = Files.createTempDirectory("player-switcher-real-player-test").toFile()
    private lateinit var model: PlayerModel
    private lateinit var switcher: PlayerSwitcher

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setPodcastsEnabled(true)
        coEvery { songService.byId(any()) } returns null
        model = PlayerModel(
            rpcServiceManager = mockk<RpcServiceManager>(relaxed = true),
            audioPlayer = audioPlayer,
            songService = songService,
            userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
            albumService = mockk<AlbumServiceWrapper>(relaxed = true),
            artistService = mockk<ArtistServiceWrapper>(relaxed = true),
            userService = mockk<UserServiceWrapper>(relaxed = true),
            radioService = mockk<RadioServiceWrapper>(relaxed = true),
            songCache = SongCache(),
            settings = InMemorySettings(),
            snackbarManager = mockk<SnackbarManager>(relaxed = true),
            cbor = AppCbor,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )
        switcher = PlayerSwitcher(model, podcast, dispatchers)
        scheduler.runCurrent()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
        stateDir.deleteRecursively()
    }

    private fun startSongs(count: Int = 3) {
        model.playQueue(queueOf(testSongs(count)))
        scheduler.runCurrent()
        assertTrue(model.isPlaying.value)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    private fun startPodcast() {
        podcast.content.value = true
        podcast.playing.value = true
        scheduler.runCurrent()
        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
    }

    @Test
    fun finishedEventOfAPausedSongLeavesThePodcastPlayingAndActive() {
        startSongs()
        model.pause()
        scheduler.runCurrent()
        assertFalse(model.isPlaying.value)
        startPodcast()

        audioPlayer.emitFinished()
        scheduler.runCurrent()

        assertEquals(ActivePlayer.PODCAST, switcher.active.value)
        assertEquals(emptyList<Call>(), podcast.calls)
        assertFalse(model.isPlaying.value)
        assertFalse(model.playbackRequested)
        assertTrue(switcher.isPlaying.value)
    }

    @Test
    fun songStartedByTheUserPausesThePlayingPodcastAndActivatesSongs() {
        startPodcast()

        model.playQueue(queueOf(testSongs(3)))
        scheduler.runCurrent()

        assertTrue(model.isPlaying.value)
        assertEquals(listOf<Call>(Call.Pause), podcast.calls)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
    }

    @Test
    fun podcastStartingPausesThePlayingSongAndActivatesThePodcast() {
        startSongs()

        startPodcast()

        assertFalse(model.isPlaying.value)
        assertFalse(model.playbackRequested)
        assertEquals(emptyList<Call>(), podcast.calls)
    }

    @Test
    fun trackEndWhileSongsAreActiveKeepsSongsActiveAndLeavesTheIdlePodcastAlone() {
        podcast.content.value = true
        startSongs()

        audioPlayer.emitFinished()
        scheduler.runCurrent()

        assertEquals(1, model.currentIndex.value)
        assertTrue(model.isPlaying.value)
        assertEquals(ActivePlayer.SONGS, switcher.active.value)
        assertTrue(switcher.isPlaying.value)
        assertEquals(emptyList<Call>(), podcast.calls)
    }
}
