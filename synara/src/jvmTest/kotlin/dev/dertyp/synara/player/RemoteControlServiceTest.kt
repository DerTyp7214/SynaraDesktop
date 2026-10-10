package dev.dertyp.synara.player

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IClientRequestService
import dev.dertyp.services.IRemoteControlService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.testing.testSongs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlServiceTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))

    private val requests = MutableSharedFlow<ClientRequest>(extraBufferCapacity = 16)
    private val isConnected = MutableStateFlow(true)

    private val currentSong = MutableStateFlow<UserSong?>(null)
    private val isPlaying = MutableStateFlow(false)
    private val currentIndex = MutableStateFlow(0)
    private val shuffleMode = MutableStateFlow(false)
    private val repeatMode = MutableStateFlow(RepeatMode.OFF)
    private val volume = MutableStateFlow(1f)
    private val seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    private val queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val currentPosition = MutableStateFlow(0L)
    private val duration = MutableStateFlow(0L)
    private val currentSource = MutableStateFlow<PlaybackSource?>(null)
    private val syncedVersion = MutableStateFlow(0L)

    private val reports = mutableListOf<RemotePlaybackStatus>()
    private var failReports = false

    private val presenceService = mockk<PresenceService>(relaxed = true)
    private val clientRequestService = mockk<IClientRequestService>(relaxed = true)
    private val remoteControlService = mockk<IRemoteControlService>(relaxed = true)
    private val queueSyncService = mockk<QueueSyncService>(relaxed = true)
    private val remotePlaybackController = mockk<RemotePlaybackController>(relaxed = true)
    private val playerModel = mockk<PlayerModel>(relaxed = true)

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsRemoteControlEnabled(true)

        every { presenceService.requests } returns requests
        every { presenceService.isConnected } returns isConnected

        every { playerModel.currentSong } returns currentSong
        every { playerModel.isPlaying } returns isPlaying
        every { playerModel.currentIndex } returns currentIndex
        every { playerModel.shuffleMode } returns shuffleMode
        every { playerModel.repeatMode } returns repeatMode
        every { playerModel.volume } returns volume
        every { playerModel.seekEvents } returns seekEvents
        every { playerModel.queue } returns queue
        every { playerModel.currentPosition } returns currentPosition
        every { playerModel.duration } returns duration
        every { playerModel.currentSource } returns currentSource

        every { queueSyncService.syncedVersionFlow } returns syncedVersion

        coEvery { remoteControlService.reportStatus(capture(reports)) } answers {
            if (failReports) throw IllegalStateException("report failed")
        }
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun startService(): RemoteControlService {
        val service = RemoteControlService(
            presenceService,
            clientRequestService,
            remoteControlService,
            queueSyncService,
            remotePlaybackController,
            playerModel,
            mockk<Logger>(relaxed = true),
            dispatchers
        )
        service.start()
        scheduler.runCurrent()
        return service
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun send(command: PlaybackCommand): ClientRequest.ControlPlayback {
        val request = ClientRequest.ControlPlayback(
            id = randomPlatformUUID(),
            requestedAt = 0L,
            requestedBySessionId = randomPlatformUUID(),
            requestedByDeviceName = "Controller",
            command = command
        )
        assertTrue(requests.tryEmit(request))
        scheduler.runCurrent()
        return request
    }

    private fun assertCompletedWith(request: ClientRequest, status: ClientRequestStatus) {
        coVerify(exactly = 1) { clientRequestService.complete(request.id, status) }
        coVerify(exactly = 1) { clientRequestService.complete(request.id, any()) }
    }

    @Test
    fun transportCommandsWithACurrentSongCallTheirPlayerMethodAndComplete() {
        currentSong.value = testSong(0)
        startService()

        val play = send(PlaybackCommand.Play)
        verify(exactly = 1) { playerModel.play() }
        assertCompletedWith(play, ClientRequestStatus.COMPLETED)

        val pause = send(PlaybackCommand.Pause)
        verify(exactly = 1) { playerModel.pause() }
        assertCompletedWith(pause, ClientRequestStatus.COMPLETED)

        val toggle = send(PlaybackCommand.TogglePlayPause)
        verify(exactly = 1) { playerModel.togglePlayPause() }
        assertCompletedWith(toggle, ClientRequestStatus.COMPLETED)

        val next = send(PlaybackCommand.Next)
        verify(exactly = 1) { playerModel.skipNext() }
        assertCompletedWith(next, ClientRequestStatus.COMPLETED)

        val previous = send(PlaybackCommand.Previous)
        verify(exactly = 1) { playerModel.skipPrevious() }
        assertCompletedWith(previous, ClientRequestStatus.COMPLETED)
    }

    @Test
    fun transportCommandsWithoutACurrentSongAreRejectedWithoutTouchingThePlayer() {
        startService()

        val sent = listOf(
            PlaybackCommand.Play,
            PlaybackCommand.Pause,
            PlaybackCommand.TogglePlayPause,
            PlaybackCommand.Next,
            PlaybackCommand.Previous
        ).map { send(it) }

        sent.forEach { assertCompletedWith(it, ClientRequestStatus.REJECTED) }
        verify(exactly = 0) { playerModel.play() }
        verify(exactly = 0) { playerModel.pause() }
        verify(exactly = 0) { playerModel.togglePlayPause() }
        verify(exactly = 0) { playerModel.skipNext() }
        verify(exactly = 0) { playerModel.skipPrevious() }
    }

    @Test
    fun seekIsClampedToTheDurationAndOnlyFlooredWhenTheDurationIsUnknown() {
        currentSong.value = testSong(0)
        duration.value = 200_000L
        startService()

        val inside = send(PlaybackCommand.SeekTo(1_000L))
        verify(exactly = 1) { playerModel.seekTo(1_000L) }
        assertCompletedWith(inside, ClientRequestStatus.COMPLETED)

        send(PlaybackCommand.SeekTo(-5L))
        verify(exactly = 1) { playerModel.seekTo(0L) }

        send(PlaybackCommand.SeekTo(999_999L))
        verify(exactly = 1) { playerModel.seekTo(200_000L) }
        verify(exactly = 0) { playerModel.seekTo(999_999L) }

        duration.value = 0L

        send(PlaybackCommand.SeekTo(999_999L))
        verify(exactly = 1) { playerModel.seekTo(999_999L) }

        send(PlaybackCommand.SeekTo(-5L))
        verify(exactly = 2) { playerModel.seekTo(0L) }
    }

    @Test
    fun shuffleIsRejectedOnAnEndlessSourceAndVolumeIsClampedToOne() {
        currentSource.value = PlaybackSource.Radio(randomPlatformUUID())
        startService()

        val shuffle = send(PlaybackCommand.SetShuffle(true))
        assertCompletedWith(shuffle, ClientRequestStatus.REJECTED)
        verify(exactly = 0) { playerModel.setShuffleMode(any()) }

        val loud = send(PlaybackCommand.SetVolume(1.7f))
        assertCompletedWith(loud, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.setVolume(1f) }
        verify(exactly = 1) { playerModel.setVolume(any()) }

        currentSource.value = PlaybackSource.Manual

        val allowed = send(PlaybackCommand.SetShuffle(true))
        assertCompletedWith(allowed, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.setShuffleMode(true) }
    }

    @Test
    fun playQueueItemIsRejectedWithoutSkippingWhenTheQueueVersionNeverArrives() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns false
        every { playerModel.skipToQueueId(any()) } returns true
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.REJECTED)
        verify(exactly = 0) { playerModel.skipToQueueId(any()) }
    }

    @Test
    fun playQueueItemIsRejectedWhenTheEntryIsNotInTheQueue() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns true
        every { playerModel.skipToQueueId(42L) } returns false
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.REJECTED)
        verify(exactly = 1) { playerModel.skipToQueueId(42L) }
    }

    @Test
    fun playQueueItemCompletesWhenTheVersionIsSyncedAndTheEntryExists() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns true
        every { playerModel.skipToQueueId(42L) } returns true
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.skipToQueueId(42L) }
        verify(exactly = 0) { playerModel.play() }
    }

    @Test
    fun playQueueItemOnTheCurrentPausedEntryStartsPlayback() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns true
        every { playerModel.skipToQueueId(42L) } returns true
        every { playerModel.playbackRequested } returns false
        queue.value = listOf(QueueEntry.Explicit(testSong(0), queueId = 42L), QueueEntry.Explicit(testSong(1), queueId = 43L))
        currentIndex.value = 0
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.skipToQueueId(42L) }
        verify(exactly = 1) { playerModel.play() }
    }

    @Test
    fun playQueueItemOnTheCurrentPlayingEntryDoesNotRestartIt() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns true
        every { playerModel.skipToQueueId(42L) } returns true
        every { playerModel.playbackRequested } returns true
        queue.value = listOf(QueueEntry.Explicit(testSong(0), queueId = 42L))
        currentIndex.value = 0
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 42L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        verify(exactly = 0) { playerModel.play() }
    }

    @Test
    fun playQueueItemOnAnotherEntryLeavesTheResumeToTheSkip() {
        coEvery { queueSyncService.ensureVersion(5L, any()) } returns true
        every { playerModel.skipToQueueId(43L) } returns true
        every { playerModel.playbackRequested } returns false
        queue.value = listOf(QueueEntry.Explicit(testSong(0), queueId = 42L), QueueEntry.Explicit(testSong(1), queueId = 43L))
        currentIndex.value = 0
        startService()

        val request = send(PlaybackCommand.PlayQueueItem(queueId = 43L, queueVersion = 5L))

        assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.skipToQueueId(43L) }
        verify(exactly = 0) { playerModel.play() }
    }

    @Test
    fun theRemoteTargetIsDeselectedAndTheControllerMarkedBeforeTheCommandIsApplied() {
        currentSong.value = testSong(0)
        startService()

        val request = send(PlaybackCommand.Play)

        verifyOrder {
            remotePlaybackController.deselect()
            presenceService.markControlled(request.requestedBySessionId, request.requestedByDeviceName)
            playerModel.play()
        }
    }

    @Test
    fun aPlayerExceptionRejectsTheRequestAndLaterRequestsAreStillHandled() {
        currentSong.value = testSong(0)
        every { playerModel.play() } throws IllegalStateException("player failed")
        startService()

        val failed = send(PlaybackCommand.Play)
        assertCompletedWith(failed, ClientRequestStatus.REJECTED)

        val later = send(PlaybackCommand.Pause)
        verify(exactly = 1) { playerModel.pause() }
        assertCompletedWith(later, ClientRequestStatus.COMPLETED)
    }

    @Test
    fun anAppliedCommandReportsTheStatusBeforeCompletingTheRequest() {
        currentSong.value = testSong(0)
        startService()

        val request = send(PlaybackCommand.Play)

        assertEquals(1, reports.size)
        coVerifyOrder {
            remoteControlService.reportStatus(any())
            clientRequestService.complete(request.id, ClientRequestStatus.COMPLETED)
        }
    }

    @Test
    fun theFirstReportFollowsOneSecondAfterTheConnectionIsUp() {
        isConnected.value = false
        startService()
        advance(5_000)
        assertEquals(0, reports.size)

        isConnected.value = true
        scheduler.runCurrent()

        advance(999)
        assertEquals(0, reports.size)

        advance(151)
        assertEquals(1, reports.size)
    }

    @Test
    fun aBurstOfPlayerChangesYieldsOneReportAfterTheCoalesceWindow() {
        val songs = testSongs(2)
        currentSong.value = songs[0]
        startService()
        advance(1_150)
        assertEquals(1, reports.size)

        volume.value = 0.4f
        shuffleMode.value = true
        repeatMode.value = RepeatMode.ALL
        currentIndex.value = 1
        currentSong.value = songs[1]
        syncedVersion.value = 3L
        assertTrue(seekEvents.tryEmit(5_000L))
        scheduler.runCurrent()

        advance(149)
        assertEquals(1, reports.size)

        advance(1)
        assertEquals(2, reports.size)

        advance(5_000)
        assertEquals(2, reports.size)
    }

    @Test
    fun everySingleSourceChangeYieldsExactlyOneReportCarryingTheNewValue() {
        val songs = testSongs(2)
        val entries = songs.map { QueueEntry.Explicit(it) }
        queue.value = entries
        currentSong.value = songs[0]
        startService()
        advance(1_150)
        assertEquals(1, reports.size)

        val steps = listOf<Pair<() -> Unit, (RemotePlaybackStatus) -> Unit>>(
            Pair({ currentSong.value = songs[1] }, { assertEquals(songs[1].id, it.songId) }),
            Pair({ currentIndex.value = 1 }, { assertEquals(entries[1].queueId, it.currentQueueId) }),
            Pair({ shuffleMode.value = true }, { assertTrue(it.isShuffled) }),
            Pair({ repeatMode.value = RepeatMode.ALL }, { assertEquals(RepeatMode.ALL, it.repeatMode) }),
            Pair(
                {
                    currentPosition.value = 5_000L
                    assertTrue(seekEvents.tryEmit(5_000L))
                },
                { assertEquals(5_000L, it.positionMs) }
            ),
            Pair({ syncedVersion.value = 3L }, { assertEquals(3L, it.queueVersion) }),
            Pair({ volume.value = 0.4f }, { assertEquals(0.4f, it.volume) }),
            Pair({ isPlaying.value = true }, { assertTrue(it.isPlaying) })
        )

        steps.forEachIndexed { index, (change, verifyReport) ->
            change()
            scheduler.runCurrent()

            advance(149)
            assertEquals(index + 1, reports.size, "source $index reported before the coalesce window")

            advance(1)
            assertEquals(index + 2, reports.size, "source $index did not yield exactly one report")
            verifyReport(reports.last())
        }
    }

    @Test
    fun setRepeatAppliesEveryModeAndCompletesEvenWithoutACurrentSong() {
        startService()

        RepeatMode.entries.forEach { mode ->
            val request = send(PlaybackCommand.SetRepeat(mode))

            verify(exactly = 1) { playerModel.setRepeatMode(mode) }
            assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        }
        verify(exactly = RepeatMode.entries.size) { playerModel.setRepeatMode(any()) }

        currentSong.value = testSong(0)
        scheduler.runCurrent()

        RepeatMode.entries.forEach { mode ->
            val request = send(PlaybackCommand.SetRepeat(mode))

            verify(exactly = 2) { playerModel.setRepeatMode(mode) }
            assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        }
    }

    @Test
    fun seekWithoutACurrentSongIsRejectedWithoutTouchingThePlayer() {
        duration.value = 200_000L
        startService()

        val request = send(PlaybackCommand.SeekTo(1_000L))

        assertCompletedWith(request, ClientRequestStatus.REJECTED)
        verify(exactly = 0) { playerModel.seekTo(any()) }
    }

    @Test
    fun aVolumeBelowZeroIsClampedToZero() {
        startService()

        val request = send(PlaybackCommand.SetVolume(-0.5f))

        assertCompletedWith(request, ClientRequestStatus.COMPLETED)
        verify(exactly = 1) { playerModel.setVolume(0f) }
        verify(exactly = 1) { playerModel.setVolume(any()) }
    }

    @Test
    fun aRejectedCommandSendsNoStatusReport() {
        startService()
        advance(1_150)
        assertEquals(1, reports.size)

        val request = send(PlaybackCommand.Play)
        assertCompletedWith(request, ClientRequestStatus.REJECTED)
        assertEquals(1, reports.size)

        advance(5_000)
        assertEquals(1, reports.size)
    }

    @Test
    fun theReportMirrorsThePlayerStateAndTheCurrentQueueEntry() {
        val songs = testSongs(3)
        val entries = songs.map { QueueEntry.Explicit(it) }
        queue.value = entries
        currentIndex.value = 1
        currentSong.value = songs[1]
        isPlaying.value = true
        currentPosition.value = 1_234L
        duration.value = 240_000L
        shuffleMode.value = true
        repeatMode.value = RepeatMode.ALL
        volume.value = 0.5f
        syncedVersion.value = 9L
        startService()

        advance(1_150)

        assertEquals(
            listOf(
                RemotePlaybackStatus(
                    songId = songs[1].id,
                    isPlaying = true,
                    positionMs = 1_234L,
                    durationMs = 240_000L,
                    isShuffled = true,
                    repeatMode = RepeatMode.ALL,
                    volume = 0.5f,
                    currentQueueId = entries[1].queueId,
                    queueVersion = 9L
                )
            ),
            reports
        )
    }

    @Test
    fun theReportLeavesVersionDurationAndQueueEntryEmptyWhenThereIsNothingToReport() {
        startService()

        advance(1_150)

        val report = reports.single()
        assertNull(report.queueVersion)
        assertNull(report.durationMs)
        assertNull(report.currentQueueId)
        assertNull(report.songId)
    }

    @Test
    fun aHeartbeatReportIsSentEveryTenSecondsWhilePlayingAndNoneAfterPause() {
        currentSong.value = testSong(0)
        isPlaying.value = true
        startService()

        advance(2_000)
        assertEquals(1, reports.size)

        advance(10_000)
        assertEquals(2, reports.size)

        advance(10_000)
        assertEquals(3, reports.size)

        isPlaying.value = false
        scheduler.runCurrent()
        advance(1_000)
        assertEquals(4, reports.size)

        advance(60_000)
        assertEquals(4, reports.size)
    }

    @Test
    fun theHeartbeatStartsWhenPlaybackStartsAndIsSilentWhilePaused() {
        currentSong.value = testSong(0)
        startService()

        advance(30_000)
        assertEquals(1, reports.size)

        isPlaying.value = true
        scheduler.runCurrent()
        advance(1_000)
        assertEquals(2, reports.size)

        advance(10_000)
        assertEquals(3, reports.size)
    }

    @Test
    fun nothingIsReportedWhileRemoteControlIsDisabled() {
        Config.setIsRemoteControlEnabled(false)
        currentSong.value = testSong(0)
        isPlaying.value = true
        startService()

        advance(30_000)
        volume.value = 0.3f
        scheduler.runCurrent()
        advance(1_000)

        assertEquals(0, reports.size)
    }

    @Test
    fun nothingIsReportedWhileDisconnected() {
        isConnected.value = false
        currentSong.value = testSong(0)
        isPlaying.value = true
        startService()

        advance(30_000)
        volume.value = 0.3f
        scheduler.runCurrent()
        advance(1_000)

        assertEquals(0, reports.size)
    }

    @Test
    fun reportsStopWhenTheConnectionDrops() {
        currentSong.value = testSong(0)
        isPlaying.value = true
        startService()
        advance(2_000)
        assertEquals(1, reports.size)

        isConnected.value = false
        scheduler.runCurrent()
        advance(60_000)
        volume.value = 0.3f
        scheduler.runCurrent()
        advance(1_000)

        assertEquals(1, reports.size)
    }

    @Test
    fun aFailingReportIsSwallowedAndLaterTriggersStillReport() {
        currentSong.value = testSong(0)
        failReports = true
        startService()

        advance(1_150)
        assertEquals(1, reports.size)

        val request = send(PlaybackCommand.Play)
        assertEquals(2, reports.size)
        assertCompletedWith(request, ClientRequestStatus.COMPLETED)

        failReports = false
        volume.value = 0.3f
        scheduler.runCurrent()
        advance(150)

        assertEquals(3, reports.size)
    }
}
