package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.ServerClock
import dev.dertyp.synara.rpc.services.*
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.testing.*
import dev.dertyp.synara.ui.models.SnackbarManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHandBackRealPlayerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val audioPlayer = FakeAudioPlayer()
    private val stateDir: File = Files.createTempDirectory("remote-hand-back-real-player-test").toFile()
    private val statuses = MutableSharedFlow<RemotePlaybackStatus>(extraBufferCapacity = 16)
    private val device = OnlineDevice(
        sessionId = randomPlatformUUID(),
        deviceName = "Device",
        capabilities = setOf(ClientCapability.QUEUE_SYNC, ClientCapability.REMOTE_CONTROL),
        isCurrent = false,
        connectedAt = 0L
    )
    private val songs = testSongs(3)
    private val commands = mutableListOf<PlaybackCommand>()
    private lateinit var model: PlayerModel
    private lateinit var controller: RemotePlaybackController

    @BeforeTest
    fun setUp() {
        startTestKoin()
        val modelSongService = mockk<SongServiceWrapper>(relaxed = true)
        coEvery { modelSongService.byId(any()) } returns null
        model = PlayerModel(
            rpcServiceManager = mockk<RpcServiceManager>(relaxed = true),
            audioPlayer = audioPlayer,
            songService = modelSongService,
            userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
            albumService = mockk<AlbumServiceWrapper>(relaxed = true),
            artistService = mockk<ArtistServiceWrapper>(relaxed = true),
            userService = mockk<UserServiceWrapper>(relaxed = true),
            radioService = mockk<RadioServiceWrapper>(relaxed = true),
            songCache = SongCache(),
            timecodeTagStore = mockk<TimecodeTagStore>(relaxed = true),
            settings = InMemorySettings(),
            snackbarManager = mockk<SnackbarManager>(relaxed = true),
            cbor = AppCbor,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )

        val presenceService = mockk<PresenceService>(relaxed = true)
        every { presenceService.onlineDevices } returns MutableStateFlow(listOf(device))
        val remoteControlService = mockk<IRemoteControlService>()
        every { remoteControlService.observeStatus(any()) } returns statuses
        coEvery { remoteControlService.sendCommand(any(), capture(commands)) } returns ClientRequestStatus.COMPLETED
        val songService = mockk<ISongService>()
        coEvery { songService.byId(any()) } returns null
        val serverClock = mockk<ServerClock>()
        every { serverClock.serverNow() } returns 0L
        controller = RemotePlaybackController(
            presenceService,
            remoteControlService,
            mockk<QueueSyncService>(relaxed = true),
            model,
            songService,
            SongCache(),
            serverClock,
            mockk<SnackbarManager>(relaxed = true),
            mockk<Logger>(relaxed = true),
            dispatchers
        )
        scheduler.runCurrent()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
        stateDir.deleteRecursively()
    }

    private fun controlDeviceWhilePlaying(song: UserSong) {
        model.playQueue(queueOf(songs))
        scheduler.runCurrent()
        assertEquals(song.id, model.currentSong.value?.id)
        assertTrue(model.isPlaying.value)
        audioPlayer.position.value = 20_000

        controller.select(device.sessionId)
        scheduler.runCurrent()

        assertEquals(device, controller.target.value)
        assertFalse(model.isPlaying.value)
        assertNull(audioPlayer.current)
        assertEquals(0L, model.currentPosition.value)
    }

    private fun report(songId: PlatformUUID?, positionMs: Long, isPlaying: Boolean = false) {
        val status = RemotePlaybackStatus(
            songId = songId,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = 240_000L,
            isShuffled = false,
            repeatMode = RepeatMode.OFF
        )
        assertTrue(statuses.tryEmit(status))
        scheduler.runCurrent()
    }

    private fun handBack() {
        controller.select(null)
        scheduler.runCurrent()
        assertNull(controller.target.value)
        assertFalse(model.isRemote)
    }

    @Test
    fun theHandedBackSongIsShownAtThePositionOfTheDeviceAndPlayContinuesThere() {
        controlDeviceWhilePlaying(songs[0])
        report(songs[0].id, positionMs = 83_000)

        handBack()

        assertEquals(songs[0].id, model.currentSong.value?.id)
        assertEquals(83_000L, model.currentPosition.value)
        assertEquals(songs[0].duration, model.duration.value)
        assertEquals(1, audioPlayer.loads.value.size)
        assertFalse(model.isPlaying.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(listOf(songs[0].id to true, songs[0].id to true), audioPlayer.loads.value)
        assertEquals(listOf(0L, 83_000L), audioPlayer.starts.value)
        assertEquals(83_000L, model.currentPosition.value)
        assertTrue(model.isPlaying.value)
    }

    @Test
    fun handingBackAPlayingDevicePausesItAndPlayContinuesAtThePositionOfThePause() {
        controlDeviceWhilePlaying(songs[0])
        report(songs[0].id, positionMs = 80_000, isPlaying = true)

        controller.select(null)
        scheduler.runCurrent()

        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), commands)
        assertTrue(model.isRemote)

        report(songs[0].id, positionMs = 83_000)

        assertFalse(model.isRemote)
        assertNull(controller.target.value)
        assertEquals(83_000L, model.currentPosition.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(listOf(0L, 83_000L), audioPlayer.starts.value)
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), commands)
    }

    @Test
    fun aDeviceOnAnotherSongHandsBackTheCurrentEntryAtTheStart() {
        controlDeviceWhilePlaying(songs[0])
        report(songs[1].id, positionMs = 83_000)

        handBack()

        assertEquals(songs[0].id, model.currentSong.value?.id)
        assertEquals(0L, model.currentPosition.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(listOf(songs[0].id to true, songs[0].id to true), audioPlayer.loads.value)
        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
    }

    @Test
    fun aDeviceThatReportedNoPositionHandsBackTheCurrentEntryAtTheStart() {
        controlDeviceWhilePlaying(songs[0])

        handBack()

        assertEquals(0L, model.currentPosition.value)

        model.play()
        scheduler.runCurrent()

        assertEquals(listOf(0L, 0L), audioPlayer.starts.value)
    }
}
