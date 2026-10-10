package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.ServerClock
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.ui.models.SnackbarManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePlaybackControllerTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))

    private val reportTime = 1_700_000_000_000L

    private val onlineDevices = MutableStateFlow<List<OnlineDevice>>(emptyList())
    private val statuses = MutableSharedFlow<RemotePlaybackStatus>(extraBufferCapacity = 16)
    private val sessions = mutableListOf<PlatformUUID>()
    private val commands = mutableListOf<PlaybackCommand>()
    private var commandResult: () -> ClientRequestStatus = { ClientRequestStatus.COMPLETED }
    private var remotePlayHandler: ((Long) -> Unit)? = null
    private var serverNow = 0L
    private val localQueue = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val localIndex = MutableStateFlow(-1)
    private val handBacks = mutableListOf<Pair<Long, Boolean>>()

    private val presenceService = mockk<PresenceService>(relaxed = true)
    private val remoteControlService = mockk<IRemoteControlService>()
    private val queueSyncService = mockk<QueueSyncService>(relaxed = true)
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val songService = mockk<ISongService>()
    private val songCache = SongCache()
    private val serverClock = mockk<ServerClock>()
    private val snackbarManager = mockk<SnackbarManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsRemoteControlEnabled(true)

        every { presenceService.onlineDevices } returns onlineDevices
        every { remoteControlService.observeStatus(any()) } returns statuses
        coEvery { remoteControlService.sendCommand(capture(sessions), capture(commands)) } answers { commandResult() }
        every { playerModel.remotePlayHandler = any() } answers { remotePlayHandler = firstArg() }
        every { playerModel.remotePlayHandler } answers { remotePlayHandler }
        every { playerModel.queue } returns localQueue
        every { playerModel.currentIndex } returns localIndex
        every { playerModel.seekTo(any()) } answers { handBacks += firstArg<Long>() to (remotePlayHandler == null) }
        every { serverClock.serverNow() } answers { serverNow }
        coEvery { songService.byId(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun createController(): RemotePlaybackController {
        val controller = RemotePlaybackController(
            presenceService,
            remoteControlService,
            queueSyncService,
            playerModel,
            songService,
            songCache,
            serverClock,
            snackbarManager,
            mockk<Logger>(relaxed = true),
            dispatchers
        )
        scheduler.runCurrent()
        return controller
    }

    private fun controllerSelecting(device: OnlineDevice): RemotePlaybackController {
        onlineDevices.value = listOf(device)
        val controller = createController()
        controller.select(device.sessionId)
        scheduler.runCurrent()
        return controller
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun device(
        capabilities: Set<ClientCapability> = setOf(ClientCapability.QUEUE_SYNC, ClientCapability.REMOTE_CONTROL),
        isCurrent: Boolean = false
    ) = OnlineDevice(
        sessionId = randomPlatformUUID(),
        deviceName = "Device",
        capabilities = capabilities,
        isCurrent = isCurrent,
        connectedAt = 0L
    )

    private fun status(
        songId: PlatformUUID? = null,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        durationMs: Long? = null,
        isShuffled: Boolean = false,
        repeatMode: RepeatMode = RepeatMode.OFF,
        volume: Float? = null,
        reportedAt: Long = 0L,
        currentQueueId: Long? = null
    ) = RemotePlaybackStatus(
        songId = songId,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        isShuffled = isShuffled,
        repeatMode = repeatMode,
        volume = volume,
        reportedAt = reportedAt,
        currentQueueId = currentQueueId
    )

    private fun report(status: RemotePlaybackStatus) {
        assertTrue(statuses.tryEmit(status))
        scheduler.runCurrent()
    }

    private fun failingStatusStream(): Flow<RemotePlaybackStatus> = flow { throw IllegalStateException("stream failed") }

    private fun verifySnackbars(count: Int) {
        verify(exactly = count) { snackbarManager.showSnackbar(any<String>(), any(), any(), any()) }
    }

    private fun verifyDeviceRefreshes(count: Int) {
        verify(exactly = count) { presenceService.refreshOnlineDevices() }
    }

    @Test
    fun controllableDevicesExcludeThisDeviceAndDevicesWithoutRemoteControl() {
        val own = device(isCurrent = true)
        val controllable = device()
        val queueOnly = device(capabilities = setOf(ClientCapability.QUEUE_SYNC))
        onlineDevices.value = listOf(own, controllable, queueOnly)

        val controller = createController()

        assertEquals(listOf(controllable), controller.controllableDevices.value)
    }

    @Test
    fun selectingAKnownDevicePausesLocallyEnablesQueueSyncAndWatchesItsStatus() {
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(false)
        val target = device()

        val controller = controllerSelecting(target)

        verify(exactly = 1) { playerModel.pause() }
        assertTrue(Config.isQueueSyncEnabled.value)
        assertEquals(target, controller.target.value)
        assertNotNull(remotePlayHandler)
        verify(exactly = 1) { remoteControlService.observeStatus(target.sessionId) }
        verify(exactly = 1) { remoteControlService.observeStatus(any()) }
    }

    @Test
    fun selectingAnUnknownOrNullDeviceDeselects() {
        val target = device()
        val controller = controllerSelecting(target)

        controller.select(randomPlatformUUID())
        scheduler.runCurrent()
        assertNull(controller.target.value)
        assertNull(remotePlayHandler)

        controller.select(target.sessionId)
        scheduler.runCurrent()
        assertEquals(target, controller.target.value)

        controller.select(null)
        scheduler.runCurrent()
        assertNull(controller.target.value)
        assertNull(remotePlayHandler)
    }

    @Test
    fun selectingTheCurrentTargetAgainDoesNotResubscribe() {
        val target = device()
        val controller = controllerSelecting(target)

        controller.select(target.sessionId)
        scheduler.runCurrent()

        assertEquals(target, controller.target.value)
        verify(exactly = 1) { remoteControlService.observeStatus(any()) }
        verify(exactly = 1) { playerModel.pause() }
    }

    private fun assertMirroredStateIsReset(controller: RemotePlaybackController) {
        assertFalse(controller.isPlaying.value)
        assertNull(controller.currentSong.value)
        assertEquals(0L, controller.currentPosition.value)
        assertEquals(0L, controller.duration.value)
        assertEquals(1f, controller.volume.value)
        assertFalse(controller.shuffleMode.value)
        assertEquals(RepeatMode.OFF, controller.repeatMode.value)
    }

    @Test
    fun selectingAnotherDeviceEndsTheFirstWatchResetsTheMirroredStateAndWatchesTheNewDevice() {
        val song = testSong(0)
        runBlocking { songCache.put(song) }
        val first = device()
        val second = device()
        val firstStatuses = MutableSharedFlow<RemotePlaybackStatus>(extraBufferCapacity = 16)
        val secondStatuses = MutableSharedFlow<RemotePlaybackStatus>(extraBufferCapacity = 16)
        every { remoteControlService.observeStatus(first.sessionId) } returns firstStatuses
        every { remoteControlService.observeStatus(second.sessionId) } returns secondStatuses
        onlineDevices.value = listOf(first, second)
        val controller = createController()

        controller.select(first.sessionId)
        scheduler.runCurrent()
        assertEquals(1, firstStatuses.subscriptionCount.value)
        assertEquals(0, secondStatuses.subscriptionCount.value)
        val playing = status(
            songId = song.id,
            isPlaying = true,
            positionMs = 5_000L,
            durationMs = 240_000L,
            isShuffled = true,
            repeatMode = RepeatMode.ONE,
            volume = 0.3f
        )
        assertTrue(firstStatuses.tryEmit(playing))
        scheduler.runCurrent()
        assertTrue(controller.isPlaying.value)
        assertEquals(song, controller.currentSong.value)
        assertEquals(5_000L, controller.currentPosition.value)

        controller.select(second.sessionId)
        scheduler.runCurrent()

        assertEquals(second, controller.target.value)
        assertNotNull(remotePlayHandler)
        assertEquals(0, firstStatuses.subscriptionCount.value)
        assertEquals(1, secondStatuses.subscriptionCount.value)
        verify(exactly = 1) { remoteControlService.observeStatus(first.sessionId) }
        verify(exactly = 1) { remoteControlService.observeStatus(second.sessionId) }
        verify(exactly = 2) { playerModel.pause() }
        assertMirroredStateIsReset(controller)

        assertTrue(firstStatuses.tryEmit(playing))
        advance(1_000)
        assertMirroredStateIsReset(controller)

        assertTrue(
            secondStatuses.tryEmit(
                status(positionMs = 7_000L, durationMs = 100_000L, repeatMode = RepeatMode.ALL)
            )
        )
        scheduler.runCurrent()
        assertEquals(second, controller.target.value)
        assertEquals(7_000L, controller.currentPosition.value)
        assertEquals(100_000L, controller.duration.value)
        assertEquals(RepeatMode.ALL, controller.repeatMode.value)
    }

    @Test
    fun aLateUnreachableResultForThePreviousTargetKeepsTheNewTarget() {
        val first = device()
        val second = device()
        val pending = CompletableDeferred<ClientRequestStatus>()
        coEvery { remoteControlService.sendCommand(first.sessionId, any()) } coAnswers { pending.await() }
        onlineDevices.value = listOf(first, second)
        val controller = createController()
        controller.select(first.sessionId)
        scheduler.runCurrent()

        controller.skipNext()
        scheduler.runCurrent()
        coVerify(exactly = 1) { remoteControlService.sendCommand(first.sessionId, PlaybackCommand.Next) }

        controller.select(second.sessionId)
        scheduler.runCurrent()
        assertEquals(second, controller.target.value)
        assertEquals(1, statuses.subscriptionCount.value)

        assertTrue(pending.complete(ClientRequestStatus.UNREACHABLE))
        scheduler.runCurrent()

        assertEquals(second, controller.target.value)
        assertNotNull(remotePlayHandler)
        assertEquals(1, statuses.subscriptionCount.value)

        report(status(positionMs = 7_000L, durationMs = 100_000L))
        assertEquals(7_000L, controller.currentPosition.value)

        advance(60_000)
        assertEquals(second, controller.target.value)
        verify(exactly = 1) { remoteControlService.observeStatus(second.sessionId) }
    }

    @Test
    fun deselectClearsTheHandlerTheTargetAndTheMirroredState() {
        val song = testSong(0)
        runBlocking { songCache.put(song) }
        val target = device()
        val controller = controllerSelecting(target)
        val playing = status(
            songId = song.id,
            isPlaying = true,
            positionMs = 5_000L,
            durationMs = 240_000L,
            isShuffled = true,
            repeatMode = RepeatMode.ONE,
            volume = 0.3f
        )
        report(playing)
        assertTrue(controller.isPlaying.value)
        assertEquals(song, controller.currentSong.value)
        assertEquals(5_000L, controller.currentPosition.value)
        assertEquals(240_000L, controller.duration.value)
        assertEquals(0.3f, controller.volume.value)
        assertTrue(controller.shuffleMode.value)
        assertEquals(RepeatMode.ONE, controller.repeatMode.value)

        controller.deselect()
        scheduler.runCurrent()
        report(playing)
        advance(1_000)

        assertNull(remotePlayHandler)
        assertNull(controller.target.value)
        assertFalse(controller.isPlaying.value)
        assertNull(controller.currentSong.value)
        assertEquals(0L, controller.currentPosition.value)
        assertEquals(0L, controller.duration.value)
        assertEquals(1f, controller.volume.value)
        assertFalse(controller.shuffleMode.value)
        assertEquals(RepeatMode.OFF, controller.repeatMode.value)
    }

    private fun localSongId(): PlatformUUID = (localQueue.value[localIndex.value] as QueueEntry.Explicit).song.id

    private fun localEntries(vararg entries: QueueEntry, current: Int = 0) {
        localQueue.value = entries.toList()
        localIndex.value = current
    }

    @Test
    fun deselectHandsTheReportedPositionOfTheCurrentEntryBackOnceTheHandlerIsCleared() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(testSong(1)), QueueEntry.Explicit(song), current = 1)
        val controller = controllerSelecting(device())
        report(status(songId = song.id, positionMs = 83_000L, durationMs = 240_000L))

        controller.deselect()
        scheduler.runCurrent()

        assertEquals(listOf(83_000L to true), handBacks)
    }

    @Test
    fun deselectHandsBackThePositionOfAnEntryKnownByItsSongIdOnly() {
        val song = testSong(0)
        localEntries(QueueEntry.FromSource(song.id))
        val controller = controllerSelecting(device())
        report(status(songId = song.id, positionMs = 12_000L, durationMs = 240_000L))

        controller.deselect()
        scheduler.runCurrent()

        assertEquals(listOf(12_000L to true), handBacks)
    }

    @Test
    fun deselectHandsBackThePositionAPlayingDeviceHasReachedByNow() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song))
        val controller = controllerSelecting(device())
        serverNow = reportTime
        report(status(songId = song.id, isPlaying = true, positionMs = 60_000L, durationMs = 240_000L, reportedAt = reportTime))

        serverNow = reportTime + 4_000L
        controller.deselect()
        scheduler.runCurrent()

        assertEquals(listOf(64_000L to true), handBacks)
    }

    @Test
    fun deselectHandsBackNoPositionWhenTheDeviceReportedAnotherSong() {
        localEntries(QueueEntry.Explicit(testSong(0)))
        val controller = controllerSelecting(device())
        report(status(songId = testSong(1).id, positionMs = 83_000L, durationMs = 240_000L))

        controller.deselect()
        scheduler.runCurrent()

        assertEquals(emptyList(), handBacks)
        assertNull(remotePlayHandler)
    }

    @Test
    fun deselectHandsBackNoPositionWhenTheDeviceReportedNoSongOrNothingAtAll() {
        val target = device()
        localEntries(QueueEntry.Explicit(testSong(0)))
        val controller = controllerSelecting(target)

        controller.deselect()
        scheduler.runCurrent()
        controller.select(target.sessionId)
        scheduler.runCurrent()
        report(status(positionMs = 83_000L))
        controller.deselect()
        scheduler.runCurrent()

        assertEquals(emptyList(), handBacks)
    }

    @Test
    fun deselectHandsBackNoPositionWithoutACurrentEntry() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song), current = -1)
        val controller = controllerSelecting(device())
        report(status(songId = song.id, positionMs = 83_000L, durationMs = 240_000L))

        controller.deselect()
        scheduler.runCurrent()

        assertEquals(emptyList(), handBacks)
    }

    @Test
    fun aTargetThatGoesOfflineHandsItsLastPositionBack() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song))
        val controller = controllerSelecting(device())
        report(status(songId = song.id, positionMs = 83_000L, durationMs = 240_000L))

        onlineDevices.value = emptyList()
        scheduler.runCurrent()

        assertNull(controller.target.value)
        assertEquals(listOf(83_000L to true), handBacks)
    }

    @Test
    fun selectingAnotherDeviceHandsNoPositionBack() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song))
        val first = device()
        val second = device()
        onlineDevices.value = listOf(first, second)
        val controller = createController()
        controller.select(first.sessionId)
        scheduler.runCurrent()
        report(status(songId = song.id, positionMs = 83_000L, durationMs = 240_000L))

        controller.select(second.sessionId)
        scheduler.runCurrent()

        assertEquals(second, controller.target.value)
        assertEquals(emptyList(), handBacks)
    }

    private fun controllerWithAPlayingDevice(target: OnlineDevice, positionMs: Long = 60_000L): RemotePlaybackController {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song))
        val controller = controllerSelecting(target)
        serverNow = reportTime
        report(status(songId = song.id, isPlaying = true, positionMs = positionMs, durationMs = 240_000L, reportedAt = reportTime))
        return controller
    }

    @Test
    fun handingBackPausesAPlayingDeviceAndTakesOverAtThePositionOfThePause() {
        val target = device()
        val controller = controllerWithAPlayingDevice(target)

        controller.select(null)
        scheduler.runCurrent()

        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), commands)
        assertEquals(listOf(target.sessionId), sessions)
        assertEquals(target, controller.target.value)
        assertNotNull(remotePlayHandler)
        assertEquals(emptyList(), handBacks)

        serverNow = reportTime + 2_000L
        report(status(songId = localSongId(), positionMs = 61_500L, durationMs = 240_000L, reportedAt = serverNow))

        assertNull(controller.target.value)
        assertNull(remotePlayHandler)
        assertEquals(listOf(61_500L to true), handBacks)
        verifySnackbars(0)
    }

    @Test
    fun handingBackAPausedDeviceSendsNoCommand() {
        val song = testSong(0)
        localEntries(QueueEntry.Explicit(song))
        val controller = controllerSelecting(device())
        report(status(songId = song.id, positionMs = 83_000L, durationMs = 240_000L))

        controller.select(null)
        scheduler.runCurrent()

        assertEquals(emptyList<PlaybackCommand>(), commands)
        assertNull(controller.target.value)
        assertEquals(listOf(83_000L to true), handBacks)
    }

    @Test
    fun handingBackWithoutAPausedReportEndsAfterThreeSecondsAtThePositionReachedByThen() {
        val target = device()
        val controller = controllerWithAPlayingDevice(target)

        controller.select(null)
        advance(2_999)
        assertEquals(target, controller.target.value)
        assertEquals(emptyList(), handBacks)

        serverNow = reportTime + 3_000L
        advance(1)

        assertNull(controller.target.value)
        assertEquals(listOf(63_000L to true), handBacks)
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), commands)
    }

    @Test
    fun aPauseTheDeviceDoesNotCompleteHandsBackAtOnceWithoutASnackbar() {
        val controller = controllerWithAPlayingDevice(device())
        commandResult = { ClientRequestStatus.UNREACHABLE }

        controller.select(null)
        scheduler.runCurrent()

        assertNull(controller.target.value)
        assertEquals(listOf(60_000L to true), handBacks)
        verifySnackbars(0)
        verifyDeviceRefreshes(0)
    }

    @Test
    fun aFailingPauseHandsBackAtOnceWithoutASnackbar() {
        val controller = controllerWithAPlayingDevice(device())
        commandResult = { throw IllegalStateException("command failed") }

        controller.select(null)
        scheduler.runCurrent()

        assertNull(controller.target.value)
        assertEquals(listOf(60_000L to true), handBacks)
        verifySnackbars(0)
    }

    @Test
    fun selectingTheDeviceAgainWhileItIsHandedBackKeepsItControlled() {
        val target = device()
        val controller = controllerWithAPlayingDevice(target)

        controller.select(null)
        scheduler.runCurrent()
        controller.select(target.sessionId)
        scheduler.runCurrent()
        report(status(songId = localSongId(), positionMs = 61_500L, durationMs = 240_000L))
        advance(10_000)

        assertEquals(target, controller.target.value)
        assertNotNull(remotePlayHandler)
        assertEquals(emptyList(), handBacks)
    }

    @Test
    fun aPlayingDeviceThatGoesOfflineIsNotPaused() {
        val controller = controllerWithAPlayingDevice(device())

        onlineDevices.value = emptyList()
        scheduler.runCurrent()
        advance(10_000)

        assertNull(controller.target.value)
        assertEquals(emptyList<PlaybackCommand>(), commands)
    }

    @Test
    fun deselectingBecauseThisDeviceIsTakenOverDoesNotPauseThePlayingDevice() {
        val controller = controllerWithAPlayingDevice(device())

        controller.deselect()
        scheduler.runCurrent()
        advance(10_000)

        assertNull(controller.target.value)
        assertEquals(emptyList<PlaybackCommand>(), commands)
        assertEquals(listOf(60_000L to true), handBacks)
    }

    @Test
    fun selectingAnUnknownDeviceDoesNotPauseThePlayingDevice() {
        val controller = controllerWithAPlayingDevice(device())

        controller.select(randomPlatformUUID())
        scheduler.runCurrent()

        assertNull(controller.target.value)
        assertEquals(emptyList<PlaybackCommand>(), commands)
    }

    @Test
    fun setShuffleAndSetRepeatModeSendTheGivenValues() {
        val controller = controllerSelecting(device())

        controller.setShuffle(true)
        scheduler.runCurrent()
        controller.setRepeatMode(RepeatMode.ONE)
        scheduler.runCurrent()

        assertEquals(
            listOf(PlaybackCommand.SetShuffle(true), PlaybackCommand.SetRepeat(RepeatMode.ONE)),
            commands
        )
    }

    @Test
    fun theQueueIdTheDeviceReportsIsMirroredAndClearedOnDeselect() {
        val controller = controllerSelecting(device())
        assertNull(controller.currentQueueId.value)

        report(status(currentQueueId = 42L))
        assertEquals(42L, controller.currentQueueId.value)

        controller.deselect()
        scheduler.runCurrent()
        assertNull(controller.currentQueueId.value)
    }

    @Test
    fun aTargetThatGoesOfflineIsDeselected() {
        val target = device()
        val other = device()
        val controller = controllerSelecting(target)

        onlineDevices.value = listOf(other)
        scheduler.runCurrent()

        assertNull(controller.target.value)
        assertNull(remotePlayHandler)
    }

    @Test
    fun aChangedDeviceEntryReplacesTheTargetAndVolumeSupportFollowsItsCapability() {
        val target = device()
        val controller = controllerSelecting(target)
        assertFalse(controller.supportsVolume)

        val upgraded = target.copy(capabilities = target.capabilities + ClientCapability.REMOTE_VOLUME)
        onlineDevices.value = listOf(upgraded)
        scheduler.runCurrent()

        assertEquals(upgraded, controller.target.value)
        assertTrue(controller.supportsVolume)
        assertNotNull(remotePlayHandler)
        verify(exactly = 1) { remoteControlService.observeStatus(any()) }
    }

    @Test
    fun everySurfaceMethodSendsItsCommandToTheTargetSession() {
        val target = device()
        val controller = controllerSelecting(target)

        controller.togglePlayPause()
        scheduler.runCurrent()
        controller.skipNext()
        scheduler.runCurrent()
        controller.skipPrevious()
        scheduler.runCurrent()
        controller.seekTo(5_000L)
        scheduler.runCurrent()
        controller.toggleShuffle()
        scheduler.runCurrent()
        controller.toggleRepeat()
        scheduler.runCurrent()
        controller.setVolume(0.5f)
        scheduler.runCurrent()

        assertEquals(
            listOf(
                PlaybackCommand.TogglePlayPause,
                PlaybackCommand.Next,
                PlaybackCommand.Previous,
                PlaybackCommand.SeekTo(5_000L),
                PlaybackCommand.SetShuffle(true),
                PlaybackCommand.SetRepeat(RepeatMode.ALL),
                PlaybackCommand.SetVolume(0.5f)
            ),
            commands
        )
        assertEquals(List(7) { target.sessionId }, sessions)
    }

    @Test
    fun nothingIsSentWithoutATarget() {
        onlineDevices.value = listOf(device())
        val controller = createController()

        controller.togglePlayPause()
        controller.skipNext()
        controller.skipPrevious()
        controller.seekTo(5_000L)
        controller.toggleShuffle()
        controller.toggleRepeat()
        controller.setVolume(0.5f)
        scheduler.runCurrent()
        advance(1_000)

        coVerify(exactly = 0) { remoteControlService.sendCommand(any(), any()) }
    }

    @Test
    fun toggleRepeatCyclesFromTheMirroredModeAndToggleShuffleNegatesTheMirroredValue() {
        val controller = controllerSelecting(device())

        controller.toggleRepeat()
        scheduler.runCurrent()
        report(status(repeatMode = RepeatMode.ALL))
        controller.toggleRepeat()
        scheduler.runCurrent()
        report(status(repeatMode = RepeatMode.ONE))
        controller.toggleRepeat()
        scheduler.runCurrent()
        report(status(isShuffled = true))
        controller.toggleShuffle()
        scheduler.runCurrent()

        assertEquals(
            listOf(
                PlaybackCommand.SetRepeat(RepeatMode.ALL),
                PlaybackCommand.SetRepeat(RepeatMode.ONE),
                PlaybackCommand.SetRepeat(RepeatMode.OFF),
                PlaybackCommand.SetShuffle(false)
            ),
            commands
        )
    }

    @Test
    fun seekIsClampedToTheMirroredDurationAndShownAtOnce() {
        val controller = controllerSelecting(device())

        controller.seekTo(-5L)
        assertEquals(0L, controller.currentPosition.value)
        controller.seekTo(123_456L)
        assertEquals(123_456L, controller.currentPosition.value)
        scheduler.runCurrent()

        report(status(durationMs = 200_000L))

        controller.seekTo(999_999L)
        assertEquals(200_000L, controller.currentPosition.value)
        controller.seekTo(-1L)
        assertEquals(0L, controller.currentPosition.value)
        controller.seekTo(1_000L)
        assertEquals(1_000L, controller.currentPosition.value)
        scheduler.runCurrent()

        assertEquals<List<PlaybackCommand>>(
            listOf(
                PlaybackCommand.SeekTo(0L),
                PlaybackCommand.SeekTo(123_456L),
                PlaybackCommand.SeekTo(200_000L),
                PlaybackCommand.SeekTo(0L),
                PlaybackCommand.SeekTo(1_000L)
            ),
            commands
        )
    }

    @Test
    fun rapidVolumeChangesSendTheFirstThenOnlyTheLatestAfterTheThrottle() {
        val controller = controllerSelecting(device(capabilities = ClientCapability.entries.toSet()))
        val values = List(10) { (it + 1) / 10f }

        values.forEach { controller.setVolume(it) }
        assertEquals(values.last(), controller.volume.value)
        scheduler.runCurrent()
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SetVolume(values.first())), commands)

        advance(199)
        assertEquals(1, commands.size)

        advance(1)
        assertEquals(
            listOf<PlaybackCommand>(
                PlaybackCommand.SetVolume(values.first()),
                PlaybackCommand.SetVolume(values.last())
            ),
            commands
        )

        advance(5_000)
        assertEquals(2, commands.size)
        assertEquals(values.last(), controller.volume.value)
    }

    @Test
    fun aFailingCommandShowsOneSnackbarRefreshesTheDevicesOnceAndDeselects() {
        val controller = controllerSelecting(device())
        commandResult = { throw IllegalStateException("command failed") }

        controller.skipNext()
        scheduler.runCurrent()

        verifySnackbars(1)
        verifyDeviceRefreshes(1)
        assertNull(controller.target.value)
        assertNull(remotePlayHandler)
    }

    @Test
    fun anUnreachableTargetShowsOneSnackbarRefreshesTheDevicesOnceAndDeselects() {
        val controller = controllerSelecting(device())
        commandResult = { ClientRequestStatus.UNREACHABLE }

        controller.skipNext()
        scheduler.runCurrent()

        verifySnackbars(1)
        verifyDeviceRefreshes(1)
        assertNull(controller.target.value)
        assertNull(remotePlayHandler)
    }

    @Test
    fun aRejectedCommandShowsOneSnackbarAndKeepsTheTarget() {
        val target = device()
        val controller = controllerSelecting(target)
        commandResult = { ClientRequestStatus.REJECTED }

        controller.skipNext()
        scheduler.runCurrent()

        verifySnackbars(1)
        verifyDeviceRefreshes(0)
        assertEquals(target, controller.target.value)
        assertNotNull(remotePlayHandler)
    }

    @Test
    fun aTimedOutCommandShowsOneSnackbarAndKeepsTheTarget() {
        val target = device()
        val controller = controllerSelecting(target)
        commandResult = { ClientRequestStatus.TIMED_OUT }

        controller.skipNext()
        scheduler.runCurrent()

        verifySnackbars(1)
        verifyDeviceRefreshes(0)
        assertEquals(target, controller.target.value)
        assertNotNull(remotePlayHandler)
    }

    @Test
    fun aCompletedCommandIsSilent() {
        val target = device()
        val controller = controllerSelecting(target)

        controller.skipNext()
        scheduler.runCurrent()

        verifySnackbars(0)
        verifyDeviceRefreshes(0)
        assertEquals(target, controller.target.value)
    }

    @Test
    fun remotePlaySendsTheQueueEntryWithTheFlushedVersion() {
        coEvery { queueSyncService.awaitFlushed(any()) } returns 7L
        val target = device()
        controllerSelecting(target)

        assertNotNull(remotePlayHandler).invoke(42L)
        scheduler.runCurrent()

        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 7L)), commands)
        assertEquals(listOf(target.sessionId), sessions)
        verifySnackbars(0)
    }

    @Test
    fun remotePlaySendsNothingAndShowsOneSnackbarWhenTheQueueIsNotFlushed() {
        coEvery { queueSyncService.awaitFlushed(any()) } returns null
        val target = device()
        val controller = controllerSelecting(target)

        assertNotNull(remotePlayHandler).invoke(42L)
        scheduler.runCurrent()

        coVerify(exactly = 0) { remoteControlService.sendCommand(any(), any()) }
        verifySnackbars(1)
        assertEquals(target, controller.target.value)
    }

    @Test
    fun aPlayingStatusIsProjectedToTheServerClockAndAdvancedByTheTicker() {
        val controller = controllerSelecting(device())

        serverNow = reportTime + 400L
        report(status(isPlaying = true, positionMs = 1_000L, durationMs = 240_000L, reportedAt = reportTime))
        assertEquals(1_400L, controller.currentPosition.value)

        serverNow = reportTime + 650L
        advance(250)
        assertEquals(1_650L, controller.currentPosition.value)
    }

    @Test
    fun theProjectedPositionIsClampedToTheDuration() {
        val controller = controllerSelecting(device())

        serverNow = reportTime + 5_000L
        report(status(isPlaying = true, positionMs = 1_000L, durationMs = 2_000L, reportedAt = reportTime))
        assertEquals(2_000L, controller.currentPosition.value)

        serverNow = reportTime + 60_000L
        advance(250)
        assertEquals(2_000L, controller.currentPosition.value)
    }

    @Test
    fun aPausedStatusIsNotProjectedAndDoesNotTick() {
        val controller = controllerSelecting(device())

        serverNow = reportTime + 400L
        report(status(isPlaying = false, positionMs = 1_000L, durationMs = 240_000L, reportedAt = reportTime))
        assertEquals(1_000L, controller.currentPosition.value)

        serverNow = reportTime + 5_000L
        advance(1_000)
        assertEquals(1_000L, controller.currentPosition.value)
    }

    @Test
    fun aStatusWithoutAReportTimeIsNotProjected() {
        val controller = controllerSelecting(device())

        serverNow = reportTime + 400L
        report(status(isPlaying = true, positionMs = 1_000L, durationMs = 240_000L, reportedAt = 0L))
        assertEquals(1_000L, controller.currentPosition.value)

        serverNow = reportTime + 5_000L
        advance(1_000)
        assertEquals(1_000L, controller.currentPosition.value)
    }

    @Test
    fun aCachedSongIsShownWithoutAskingTheServer() {
        val song = testSong(0)
        runBlocking { songCache.put(song) }
        val controller = controllerSelecting(device())

        report(status(songId = song.id))

        assertEquals(song, controller.currentSong.value)
        coVerify(exactly = 0) { songService.byId(any()) }
    }

    @Test
    fun anUncachedSongIsFetchedOnceCachedAndNotFetchedAgainForTheSameId() {
        val song = testSong(0)
        coEvery { songService.byId(song.id) } returns song
        val controller = controllerSelecting(device())

        report(status(songId = song.id))

        assertEquals(song, controller.currentSong.value)
        coVerify(exactly = 1) { songService.byId(song.id) }
        assertEquals(song, runBlocking { songCache.get(song.id) })

        report(status(songId = song.id, positionMs = 2_000L))

        assertEquals(song, controller.currentSong.value)
        coVerify(exactly = 1) { songService.byId(any()) }
    }

    @Test
    fun aFailedSongLookupIsRetriedWhenTheSameSongIsReportedAgain() {
        val song = testSong(0)
        coEvery { songService.byId(song.id) } throws IllegalStateException("lookup failed") andThen song
        val controller = controllerSelecting(device())

        report(status(songId = song.id))
        assertNull(controller.currentSong.value)
        coVerify(exactly = 1) { songService.byId(song.id) }

        report(status(songId = song.id, positionMs = 2_000L))

        coVerify(exactly = 2) { songService.byId(song.id) }
        assertEquals(song, controller.currentSong.value)
        assertEquals(song, runBlocking { songCache.get(song.id) })
    }

    @Test
    fun threeFailedStatusStreamsFiveSecondsApartDeselect() {
        every { remoteControlService.observeStatus(any()) } answers { failingStatusStream() }
        val target = device()
        val controller = controllerSelecting(target)

        verify(exactly = 1) { remoteControlService.observeStatus(target.sessionId) }
        assertEquals(target, controller.target.value)

        advance(4_999)
        verify(exactly = 1) { remoteControlService.observeStatus(target.sessionId) }

        advance(1)
        verify(exactly = 2) { remoteControlService.observeStatus(target.sessionId) }
        assertEquals(target, controller.target.value)

        advance(5_000)
        verify(exactly = 3) { remoteControlService.observeStatus(target.sessionId) }
        assertNull(controller.target.value)
        assertNull(remotePlayHandler)

        advance(60_000)
        verify(exactly = 3) { remoteControlService.observeStatus(any()) }
    }

    @Test
    fun aStatusStreamThatEndsNormallyResetsTheFailureCount() {
        every { remoteControlService.observeStatus(any()) } returnsMany listOf(
            failingStatusStream(),
            failingStatusStream(),
            emptyFlow(),
            failingStatusStream(),
            failingStatusStream(),
            failingStatusStream()
        )
        val target = device()
        val controller = controllerSelecting(target)

        advance(20_000)
        verify(exactly = 5) { remoteControlService.observeStatus(target.sessionId) }
        assertEquals(target, controller.target.value)

        advance(5_000)
        verify(exactly = 6) { remoteControlService.observeStatus(target.sessionId) }
        assertNull(controller.target.value)
    }

    @Test
    fun aStatusStreamOfThePreviousTargetFailingAfterANewSelectionKeepsTheNewTarget() {
        val first = device()
        val second = device()
        every { remoteControlService.observeStatus(first.sessionId) } returnsMany listOf(
            failingStatusStream(),
            failingStatusStream(),
            flow {
                try {
                    awaitCancellation()
                } finally {
                    throw IllegalStateException("stream failed")
                }
            }
        )
        every { remoteControlService.observeStatus(second.sessionId) } returns statuses
        onlineDevices.value = listOf(first, second)
        val controller = createController()
        controller.select(first.sessionId)
        advance(10_000)
        verify(exactly = 3) { remoteControlService.observeStatus(first.sessionId) }
        assertEquals(first, controller.target.value)

        controller.select(second.sessionId)
        advance(10_000)

        assertEquals(second, controller.target.value)
        assertNotNull(remotePlayHandler)
        verify(exactly = 1) { remoteControlService.observeStatus(second.sessionId) }
    }

    @Test
    fun aSongLookupOfThePreviousTargetEndingAfterANewSelectionKeepsTheNewSong() {
        val first = device()
        val second = device()
        val oldSong = testSong(0)
        val newSong = testSong(1)
        val lookup = CompletableDeferred<Unit>()
        coEvery { songService.byId(oldSong.id) } coAnswers {
            withContext(NonCancellable) { lookup.await() }
            oldSong
        }
        onlineDevices.value = listOf(first, second)
        val controller = createController()
        controller.select(first.sessionId)
        scheduler.runCurrent()
        report(status(songId = oldSong.id))
        assertNull(controller.currentSong.value)

        controller.select(second.sessionId)
        scheduler.runCurrent()
        runBlocking { songCache.put(newSong) }
        report(status(songId = newSong.id))
        assertEquals(newSong, controller.currentSong.value)

        lookup.complete(Unit)
        scheduler.runCurrent()

        assertEquals(newSong, controller.currentSong.value)
        assertEquals(second, controller.target.value)
    }

    @Test
    fun remotePlayIsDroppedWhenTheTargetChangedWhileTheQueueWasFlushing() {
        val first = device()
        val second = device()
        val flushed = CompletableDeferred<Long>()
        coEvery { queueSyncService.awaitFlushed(any()) } coAnswers { flushed.await() }
        onlineDevices.value = listOf(first, second)
        val controller = createController()
        controller.select(first.sessionId)
        scheduler.runCurrent()

        assertNotNull(remotePlayHandler).invoke(42L)
        scheduler.runCurrent()
        controller.select(second.sessionId)
        scheduler.runCurrent()
        flushed.complete(7L)
        scheduler.runCurrent()

        assertEquals(emptyList<PlaybackCommand>(), commands)
        assertEquals(second, controller.target.value)
        verifySnackbars(0)
    }
}
