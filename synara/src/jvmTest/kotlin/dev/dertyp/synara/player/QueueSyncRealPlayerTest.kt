package dev.dertyp.synara.player

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.QueueItem
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.FakeQueueService.Call
import dev.dertyp.synara.player.PlayerModel.QueueChange.Anchor
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.rpc.services.AlbumServiceWrapper
import dev.dertyp.synara.rpc.services.ArtistServiceWrapper
import dev.dertyp.synara.rpc.services.RadioServiceWrapper
import dev.dertyp.synara.rpc.services.SongServiceWrapper
import dev.dertyp.synara.rpc.services.UserPlaylistServiceWrapper
import dev.dertyp.synara.rpc.services.UserServiceWrapper
import dev.dertyp.synara.settings.SettingsFactory
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.FakeAudioPlayer
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.queueOf
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.testing.testSongs
import dev.dertyp.synara.ui.models.SnackbarManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class QueueSyncRealPlayerTest {

    private companion object {
        const val DEVICE = "test"
        const val OTHER_DEVICE = "other"
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val dispatchers = TestDispatchers(dispatcher)
    private val ownSession = randomPlatformUUID()
    private val otherSession = randomPlatformUUID()
    private val fake = FakeQueueService(ownSession, DEVICE)
    private val songCache = SongCache()
    private val stateDir: File = Files.createTempDirectory("queue-sync-real-player-test").toFile()

    private val songService = mockk<SongServiceWrapper>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager>(relaxed = true)
    private val presenceService = mockk<PresenceService>(relaxed = true)
    private val controlledFlow = MutableStateFlow(false)
    private val reachableFlow = MutableStateFlow(true)

    private lateinit var player: PlayerModel
    private lateinit var service: QueueSyncService

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsQueueSyncEnabled(true)
        Config.setQueueSyncDeviceName(DEVICE)

        coEvery { songService.byId(any()) } returns null
        every { rpcServiceManager.isServerReachable } returns reachableFlow
        every { rpcServiceManager.connectionState } returns MutableStateFlow(ConnectionState.Authenticated)
        every { rpcServiceManager.sessionId } returns ownSession.toString()
        every { presenceService.requests } returns MutableSharedFlow<ClientRequest>()
        every { presenceService.isControlled } returns controlledFlow

        player = PlayerModel(
            rpcServiceManager = rpcServiceManager,
            audioPlayer = FakeAudioPlayer(),
            songService = songService,
            userPlaylistService = mockk<UserPlaylistServiceWrapper>(relaxed = true),
            albumService = mockk<AlbumServiceWrapper>(relaxed = true),
            artistService = mockk<ArtistServiceWrapper>(relaxed = true),
            userService = mockk<UserServiceWrapper>(relaxed = true),
            radioService = mockk<RadioServiceWrapper>(relaxed = true),
            songCache = songCache,
            settings = InMemorySettings(),
            snackbarManager = mockk<SnackbarManager>(relaxed = true),
            cbor = AppCbor,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers,
            settingsFactory = mockk<SettingsFactory> {
                every { getStatePath(any()) } answers { File(stateDir, firstArg<String>()).path }
            }
        )
        service = QueueSyncService(
            queueService = fake,
            clientRequestService = mockk<IClientRequestService>(relaxed = true),
            presenceService = presenceService,
            identity = DeviceIdentity(InMemorySettings()),
            playerModel = player,
            songCache = songCache,
            settings = InMemorySettings(),
            rpcServiceManager = rpcServiceManager,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = dispatchers
        )
        scheduler.runCurrent()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
        stateDir.deleteRecursively()
    }

    private fun remoteItem(song: UserSong, queueId: Long, position: Int, shuffledPosition: Int? = null) = QueueItem(
        songId = song.id,
        queueId = queueId,
        position = position,
        shuffledPosition = shuffledPosition,
        userAdded = true,
        song = song
    )

    private fun queueIds(): List<Long> = player.queue.value.map { it.queueId }

    private fun startWithUploadedLocalQueue(): List<QueueEntry> {
        val songs = testSongs(3)
        fake.register(songs)
        player.playQueue(queueOf(songs))
        scheduler.runCurrent()
        val local = player.queue.value
        assertEquals(songs.map { it.id }, local.map { (it as QueueEntry.Explicit).song.id })
        assertEquals(0, player.currentIndex.value)

        service.start()
        scheduler.runCurrent()
        assertEquals(1L, service.syncedVersionFlow.value)
        assertEquals(local.map { it.queueId }, fake.activeQueueIds)
        return local
    }

    private fun foreignAppend(song: UserSong, queueId: Long, notify: Boolean = false) = fake.store(
        items = fake.items + remoteItem(song, queueId, fake.items.size),
        sessionId = otherSession,
        deviceName = OTHER_DEVICE,
        notify = notify
    )

    @Test
    fun pullAppliedToTheRealPlayerProducesNoWrite() = runTest(dispatcher) {
        val first = testSongs(4)
        val firstItems = first.mapIndexed { index, song -> remoteItem(song, 100L + index, index, first.size - 1 - index) }
        fake.store(
            items = firstItems,
            version = 3L,
            sessionId = otherSession,
            deviceName = OTHER_DEVICE,
            currentIndex = 1,
            shuffled = true,
            repeatMode = RepeatMode.ALL
        )

        service.start()
        scheduler.runCurrent()

        assertEquals(firstItems.reversed().map { it.queueId }, queueIds())
        assertEquals(1, player.currentIndex.value)
        assertTrue(player.shuffleMode.value)
        assertEquals(RepeatMode.ALL, player.repeatMode.value)
        assertEquals(3L, service.syncedVersionFlow.value)
        assertFalse(player.isPlaying.value)

        val second = testSongs(5)
        val secondItems = second.mapIndexed { index, song -> remoteItem(song, 200L + index, index) }
        fake.store(
            items = secondItems,
            sessionId = otherSession,
            deviceName = OTHER_DEVICE,
            currentIndex = 2,
            repeatMode = RepeatMode.ONE,
            notify = true
        )
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(secondItems.map { it.queueId }, queueIds())
        assertEquals(2, player.currentIndex.value)
        assertFalse(player.shuffleMode.value)
        assertEquals(RepeatMode.ONE, player.repeatMode.value)
        assertEquals(4L, service.syncedVersionFlow.value)
        assertEquals(listOf(Call.AckSynced(3L), Call.AckSynced(4L)), fake.callsOf<Call.AckSynced>())
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        assertEquals(4L, fake.info.version)
    }

    @Test
    fun pullWhilePlayingAndControlledKeepsTheCurrentEntryAndProducesNoWrite() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val songs = local.map { (it as QueueEntry.Explicit).song }
        val uploadWrites = fake.callsOf<Call.Write>()
        controlledFlow.value = true
        assertTrue(player.isPlaying.value)

        val foreignItems = listOf(
            remoteItem(testSong(10), 900L, 0),
            remoteItem(songs[2], local[2].queueId, 1),
            remoteItem(songs[0], local[0].queueId, 2),
            remoteItem(songs[1], local[1].queueId, 3)
        )
        val foreign = fake.store(
            items = foreignItems,
            sessionId = otherSession,
            deviceName = OTHER_DEVICE,
            currentIndex = 0,
            notify = true
        )
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(foreignItems.map { it.queueId }, queueIds())
        assertEquals(fake.activeQueueIds, queueIds())
        assertEquals(2, player.currentIndex.value)
        assertEquals(local[0].queueId, player.queue.value[player.currentIndex.value].queueId)
        assertTrue(player.isPlaying.value)
        assertNull(service.pendingRemote.value)
        assertEquals(foreign.version, service.syncedVersionFlow.value)
        assertEquals(listOf(Call.AckSynced(foreign.version)), fake.callsOf<Call.AckSynced>())
        assertEquals(uploadWrites, fake.callsOf<Call.Write>())
        assertEquals(foreign.version, fake.info.version)
    }

    @Test
    fun conflictingInsertEndsWithTheServerQueuePlusTheLocalEntriesAfterCurrent() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val events = mutableListOf<QueueSyncEvent>()
        backgroundScope.launch { service.events.toList(events) }
        scheduler.runCurrent()
        val foreign = foreignAppend(testSong(10), 900L)
        val added = listOf<QueueEntry>(QueueEntry.Explicit(testSong(20), 501L), QueueEntry.Explicit(testSong(21), 502L))

        player.insertEntries(Anchor.AFTER_CURRENT, added)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        val expected = listOf(local[0].queueId, 501L, 502L, local[1].queueId, local[2].queueId, 900L)
        assertEquals(expected, queueIds())
        assertEquals(local[0].queueId, player.queue.value[player.currentIndex.value].queueId)
        val inserts = fake.callsOf<Call.Insert>()
        assertEquals(listOf(1L, foreign.version), inserts.map { it.baseVersion })
        assertEquals(1, inserts[1].position)
        assertEquals(listOf(501L, 502L), inserts[1].items.map { it.queueId })
        assertEquals(expected, fake.activeQueueIds)
        assertEquals(foreign.version + 1, fake.info.version)
        assertEquals(foreign.version + 1, service.syncedVersionFlow.value)
        assertEquals(listOf<QueueSyncEvent>(QueueSyncEvent.Merged(foreign.modifiedByDeviceName)), events)
    }

    @Test
    fun offlineTrackAdvanceMeetingAForeignAppendKeepsTheCurrentTrackAndSendsItsIndex() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val uploadWrites = fake.callsOf<Call.Write>()
        val events = mutableListOf<QueueSyncEvent>()
        backgroundScope.launch { service.events.toList(events) }
        scheduler.runCurrent()

        reachableFlow.value = false
        scheduler.runCurrent()
        player.skipNext()
        scheduler.runCurrent()
        assertEquals(1, player.currentIndex.value)
        val foreign = foreignAppend(testSong(10), 900L)
        reachableFlow.value = true
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(local.map { it.queueId } + 900L, queueIds())
        assertEquals(1, player.currentIndex.value)
        assertEquals(local[1].queueId, player.queue.value[player.currentIndex.value].queueId)
        assertTrue(player.isPlaying.value)
        assertEquals(
            listOf(Call.SetCurrentIndex(1L, 1, false), Call.SetCurrentIndex(foreign.version, 1, false)),
            fake.callsOf<Call.Write>().drop(uploadWrites.size)
        )
        assertEquals(1, fake.info.currentIndex)
        assertEquals(queueIds(), fake.activeQueueIds)
        assertEquals(foreign.version + 1, fake.info.version)
        assertEquals(foreign.version + 1, service.syncedVersionFlow.value)
        assertEquals(emptyList(), events)
        assertNull(service.pendingRemote.value)
        assertNull(service.pendingConflict.value)
    }

    @Test
    fun offlineInsertMeetingAForeignAppendEndsWithTheServerQueuePlusTheLocalEntriesAfterCurrent() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val uploadWrites = fake.callsOf<Call.Write>()
        val added = listOf<QueueEntry>(QueueEntry.Explicit(testSong(20), 501L), QueueEntry.Explicit(testSong(21), 502L))

        reachableFlow.value = false
        scheduler.runCurrent()
        player.insertEntries(Anchor.AFTER_CURRENT, added)
        scheduler.runCurrent()
        val foreign = foreignAppend(testSong(10), 900L)
        reachableFlow.value = true
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        val expected = listOf(local[0].queueId, 501L, 502L, local[1].queueId, local[2].queueId, 900L)
        assertEquals(expected, queueIds())
        assertEquals(local[0].queueId, player.queue.value[player.currentIndex.value].queueId)
        val inserts = fake.callsOf<Call.Insert>()
        assertEquals(listOf(1L, foreign.version), inserts.map { it.baseVersion })
        assertEquals(listOf(501L, 502L), inserts[1].items.map { it.queueId })
        assertEquals(uploadWrites.size + 2, fake.callsOf<Call.Write>().size)
        assertEquals(expected, fake.activeQueueIds)
        assertEquals(foreign.version + 1, fake.info.version)
        assertEquals(foreign.version + 1, service.syncedVersionFlow.value)
    }

    @Test
    fun aDismissedBannerKeepsThePlayerQueueAtTheNextTrackEndAndAsks() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val uploadWrites = fake.callsOf<Call.Write>()
        val foreign = foreignAppend(testSong(10), 900L, notify = true)
        scheduler.runCurrent()
        assertEquals(foreign, service.pendingRemote.value)
        service.dismissPendingRemote()
        scheduler.runCurrent()
        assertNull(service.pendingRemote.value)

        player.skipNext()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(foreign, service.pendingConflict.value)
        assertEquals(local.map { it.queueId }, queueIds())
        assertEquals(1, player.currentIndex.value)
        assertTrue(player.isPlaying.value)
        assertEquals(listOf<Call>(Call.SetCurrentIndex(1L, 1, false)), fake.callsOf<Call.Write>().drop(uploadWrites.size))
        assertEquals(local.map { it.queueId } + 900L, fake.activeQueueIds)
        assertEquals(1L, service.syncedVersionFlow.value)
    }

    @Test
    fun conflictingRemoveIsGoneAfterTheRebaseAndSentAgainAtTheNewVersion() = runTest(dispatcher) {
        val local = startWithUploadedLocalQueue()
        val foreign = foreignAppend(testSong(10), 900L)

        player.removeFromQueue(local[1])
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        val expected = listOf(local[0].queueId, local[2].queueId, 900L)
        assertEquals(expected, queueIds())
        assertEquals(
            listOf(
                Call.Remove(1L, listOf(local[1].queueId), false),
                Call.Remove(foreign.version, listOf(local[1].queueId), false)
            ),
            fake.callsOf<Call.Remove>()
        )
        assertEquals(expected, fake.activeQueueIds)
        assertEquals(foreign.version + 1, fake.info.version)
        assertEquals(foreign.version + 1, service.syncedVersionFlow.value)
    }
}
