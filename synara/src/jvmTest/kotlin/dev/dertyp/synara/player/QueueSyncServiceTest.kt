package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.FakeQueueService.Call
import dev.dertyp.synara.player.PlayerModel.QueueChange
import dev.dertyp.synara.player.PlayerModel.QueueChange.Anchor
import dev.dertyp.synara.player.PlayerModel.QueueSnapshot
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSyncServiceTest {

    private companion object {
        const val DEVICE = "test"
        const val OTHER_DEVICE = "other"
        const val SYNCED = 5L
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val ownSession = randomPlatformUUID()
    private val otherSession = randomPlatformUUID()
    private val fake = FakeQueueService(ownSession, DEVICE)
    private val settings = InMemorySettings()
    private val songCache = SongCache()

    private val changes = MutableSharedFlow<QueueChange>(extraBufferCapacity = 64)
    private val serial = MutableStateFlow(0L)
    private val sourceFlow = MutableStateFlow<PlaybackSource?>(null)
    private val queueFlow = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val indexFlow = MutableStateFlow(-1)
    private val playingFlow = MutableStateFlow(false)
    private var localSnapshot = QueueSnapshot(emptyList(), emptyList(), -1, false, RepeatMode.OFF, null)

    private val requestFlow = MutableSharedFlow<ClientRequest>(extraBufferCapacity = 16)
    private val controlledFlow = MutableStateFlow(false)
    private val reachableFlow = MutableStateFlow(true)
    private val connectionFlow = MutableStateFlow(ConnectionState.Authenticated)

    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val presenceService = mockk<PresenceService>(relaxed = true)
    private val clientRequestService = mockk<IClientRequestService>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager>(relaxed = true)

    private data class AppliedQueue(val snapshot: QueueSnapshot, val keepCurrentQueueId: Long?)

    private data class ForeignQueue(val info: QueueInfo, val queueIds: List<Long>)

    private val appliedQueues = mutableListOf<AppliedQueue>()
    private val foreignQueues = mutableListOf<ForeignQueue>()

    private lateinit var identity: DeviceIdentity
    private lateinit var service: QueueSyncService

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsQueueSyncEnabled(true)
        Config.setQueueSyncDeviceName(DEVICE)

        every { playerModel.queueChanges } returns changes
        every { playerModel.changeSerial } returns serial
        every { playerModel.currentSource } returns sourceFlow
        every { playerModel.queue } returns queueFlow
        every { playerModel.currentIndex } returns indexFlow
        every { playerModel.isPlaying } returns playingFlow
        every { playerModel.snapshot() } answers { localSnapshot }
        every { playerModel.reassignQueueIds(any(), any()) } answers { firstArg() }
        every { playerModel.applyRemoteQueue(any(), any()) } answers {
            appliedQueues += AppliedQueue(firstArg(), secondArg())
        }

        every { presenceService.requests } returns requestFlow
        every { presenceService.isControlled } returns controlledFlow

        every { rpcServiceManager.isServerReachable } returns reachableFlow
        every { rpcServiceManager.connectionState } returns connectionFlow
        every { rpcServiceManager.sessionId } returns ownSession.toString()

        identity = DeviceIdentity(InMemorySettings())
        service = QueueSyncService(
            queueService = fake,
            clientRequestService = clientRequestService,
            presenceService = presenceService,
            identity = identity,
            playerModel = playerModel,
            songCache = songCache,
            settings = settings,
            rpcServiceManager = rpcServiceManager,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = TestDispatchers(dispatcher)
        )
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun localEntries(count: Int, firstQueueId: Long = 1L): List<QueueEntry> = List(count) { index ->
        if (index % 2 == 0) {
            QueueEntry.Explicit(testSong(index), firstQueueId + index)
        } else {
            QueueEntry.FromSource(randomPlatformUUID(), firstQueueId + index)
        }
    }

    private fun songIdOf(entry: QueueEntry): PlatformUUID = when (entry) {
        is QueueEntry.Explicit -> entry.song.id
        is QueueEntry.FromSource -> entry.songId
    }

    private fun itemsOf(entries: List<QueueEntry>): List<QueueItem> = entries.mapIndexed { index, entry ->
        QueueItem(
            songId = songIdOf(entry),
            queueId = entry.queueId,
            position = index,
            userAdded = entry is QueueEntry.Explicit
        )
    }

    private fun remoteItems(count: Int, firstQueueId: Long = 10_000L): List<QueueItem> = List(count) { index ->
        QueueItem(songId = randomPlatformUUID(), queueId = firstQueueId + index, position = index)
    }

    private fun localQueue(entries: List<QueueEntry>, currentIndex: Int = if (entries.isEmpty()) -1 else 0) {
        queueFlow.value = entries
        indexFlow.value = currentIndex
        localSnapshot = QueueSnapshot(entries, entries, currentIndex, false, RepeatMode.OFF, null)
    }

    private fun prepareSynced(entries: List<QueueEntry> = localEntries(3), currentIndex: Int = if (entries.isEmpty()) -1 else 0) {
        localQueue(entries, currentIndex)
        fake.store(itemsOf(entries), version = SYNCED)
        settings.put(SettingKey.QueueSyncVersion, SYNCED)
    }

    private fun startSynced(
        entries: List<QueueEntry> = localEntries(3),
        currentIndex: Int = if (entries.isEmpty()) -1 else 0
    ): List<QueueEntry> {
        prepareSynced(entries, currentIndex)
        service.start()
        scheduler.runCurrent()
        assertEquals(SYNCED, service.syncedVersionFlow.value)
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        return entries
    }

    private fun emit(change: QueueChange) {
        assertTrue(changes.tryEmit(change))
        serial.value++
    }

    private fun insertChange(queueId: Long, index: Int = 0) =
        QueueChange.Inserted(Anchor.END, listOf(QueueEntry.FromSource(randomPlatformUUID(), queueId)), index)

    private fun foreignWrite(
        items: List<QueueItem>,
        notify: Boolean = true,
        currentIndex: Int = 0,
        shuffled: Boolean = false,
        repeatMode: RepeatMode = RepeatMode.OFF,
        sourceId: String? = null
    ) = fake.store(
        items = items,
        sessionId = otherSession,
        deviceName = OTHER_DEVICE,
        currentIndex = currentIndex,
        shuffled = shuffled,
        repeatMode = repeatMode,
        sourceId = sourceId,
        notify = notify
    )

    private fun uploadRequest() = ClientRequest.UploadQueue(
        id = randomPlatformUUID(),
        requestedAt = 0L,
        requestedBySessionId = otherSession
    )

    private fun conflictNextInserts(count: Int) {
        var remaining = count
        fake.onCall = { call ->
            if (call is Call.Insert && remaining > 0) {
                remaining--
                val items = remoteItems(2, 10_000L * (foreignQueues.size + 1))
                foreignQueues += ForeignQueue(foreignWrite(items, notify = false), items.map { it.queueId })
            }
        }
    }

    private fun appliedQueueIds(): List<List<Long>> =
        appliedQueues.map { applied -> applied.snapshot.original.map { it.queueId } }

    private fun raiseConflictPrompt(currentQueueId: Long) {
        conflictNextInserts(QueueSyncService.MAX_CONFLICTS)
        repeat(QueueSyncService.MAX_CONFLICTS) { round ->
            emit(insertChange(200L + round))
            scheduler.runCurrent()
        }
        assertEquals(QueueSyncService.MAX_CONFLICTS, foreignQueues.size)
        val rebased = foreignQueues.dropLast(1)
        assertEquals(foreignQueues.last().info, service.pendingConflict.value)
        assertEquals(fake.info, service.pendingConflict.value)
        assertEquals(
            listOf(SYNCED) + rebased.map { it.info.version },
            fake.callsOf<Call.Insert>().map { it.baseVersion }
        )
        assertEquals(rebased.map { it.queueIds }, appliedQueueIds())
        assertEquals(rebased.map<ForeignQueue, Long?> { currentQueueId }, appliedQueues.map { it.keepCurrentQueueId })
        assertEquals(rebased.last().info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun insertedChangeIsSentAtTheSyncedVersionAndTheNewVersionIsPersisted() = runTest(dispatcher) {
        startSynced()
        val song = testSong(7)
        val explicit = QueueEntry.Explicit(song, 101L)
        val fromSource = QueueEntry.FromSource(randomPlatformUUID(), 102L)

        emit(QueueChange.Inserted(Anchor.END, listOf(explicit, fromSource), 2))
        scheduler.runCurrent()

        val expected = Call.Insert(
            baseVersion = SYNCED,
            position = 2,
            items = listOf(
                QueueItem(songId = song.id, queueId = 101L, position = 0, shuffledPosition = null, userAdded = true),
                QueueItem(songId = fromSource.songId, queueId = 102L, position = 0, shuffledPosition = null, userAdded = false)
            ),
            force = false
        )
        assertEquals(listOf<Call>(expected), fake.callsOf<Call.Write>())
        assertEquals(SYNCED + 1, service.syncedVersionFlow.value)
        assertEquals(SYNCED + 1, settings.get(SettingKey.QueueSyncVersion, 0L))
    }

    @Test
    fun removedMovedAndModeChangesAreSentAtTheCurrentBaseVersion() = runTest(dispatcher) {
        val entries = startSynced()

        emit(QueueChange.Removed(listOf(entries[2].queueId)))
        scheduler.runCurrent()
        emit(QueueChange.Moved(entries[0].queueId, 1))
        scheduler.runCurrent()
        emit(QueueChange.ModesChanged(true, RepeatMode.ALL))
        scheduler.runCurrent()

        assertEquals(
            listOf(
                Call.Remove(SYNCED, listOf(entries[2].queueId), false),
                Call.Move(SYNCED + 1, entries[0].queueId, 1, false),
                Call.SetModes(SYNCED + 2, true, RepeatMode.ALL, false)
            ),
            fake.callsOf<Call.Write>()
        )
        assertEquals(SYNCED + 3, service.syncedVersionFlow.value)
    }

    @Test
    fun currentChangesInsideTheDebounceWindowCollapseIntoTheLastIndex() = runTest(dispatcher) {
        val entries = startSynced()

        emit(QueueChange.CurrentChanged(entries[1].queueId, 1))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(200)
        emit(QueueChange.CurrentChanged(entries[2].queueId, 2))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(200)
        emit(QueueChange.CurrentChanged(entries[0].queueId, 0))
        scheduler.runCurrent()

        scheduler.advanceTimeBy(QueueSyncService.CURRENT_DEBOUNCE.inWholeMilliseconds - 1)
        scheduler.runCurrent()
        assertEquals(emptyList(), fake.callsOf<Call.Write>())

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(listOf<Call>(Call.SetCurrentIndex(SYNCED, 0, false)), fake.callsOf<Call.Write>())
    }

    @Test
    fun anotherChangeInsideTheDebounceWindowFlushesTheCurrentIndexFirst() = runTest(dispatcher) {
        val entries = startSynced()
        val startedAt = scheduler.currentTime

        emit(QueueChange.CurrentChanged(entries[1].queueId, 1))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(100)
        emit(QueueChange.Removed(listOf(entries[2].queueId)))
        scheduler.runCurrent()

        assertEquals(
            listOf(
                Call.SetCurrentIndex(SYNCED, 1, false),
                Call.Remove(SYNCED + 1, listOf(entries[2].queueId), false)
            ),
            fake.callsOf<Call.Write>()
        )
        assertEquals(100L, scheduler.currentTime - startedAt)
    }

    @Test
    fun ownNewerInfoAdvancesTheVersionWithoutFetchingTheQueue() = runTest(dispatcher) {
        startSynced()
        fake.calls.clear()

        fake.updates.tryEmit(fake.info.copy(version = SYNCED + 4, modifiedBySessionId = ownSession))
        scheduler.runCurrent()

        assertEquals(SYNCED + 4, service.syncedVersionFlow.value)
        assertEquals(SYNCED + 4, settings.get(SettingKey.QueueSyncVersion, 0L))
        assertEquals(emptyList(), fake.calls)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun foreignInfoAtOrBelowTheSyncedVersionIsIgnored() = runTest(dispatcher) {
        startSynced()
        fake.calls.clear()

        fake.updates.tryEmit(fake.info.copy(modifiedBySessionId = otherSession))
        fake.updates.tryEmit(fake.info.copy(version = SYNCED - 1, modifiedBySessionId = otherSession))
        scheduler.runCurrent()

        assertEquals(SYNCED, service.syncedVersionFlow.value)
        assertEquals(emptyList(), fake.calls)
        assertNull(service.pendingRemote.value)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun foreignNewerQueueIsPulledPageByPageAndAppliedOnceWhileNotPlaying() = runTest(dispatcher) {
        startSynced()
        val total = QueueSyncService.PAGE_SIZE + 1
        val remote = List(total) { index ->
            QueueItem(
                songId = randomPlatformUUID(),
                queueId = 10_000L + index,
                position = index,
                shuffledPosition = total - 1 - index
            )
        }

        val info = foreignWrite(remote, currentIndex = 2, shuffled = true, repeatMode = RepeatMode.ALL, sourceId = "album")
        scheduler.runCurrent()

        assertEquals(
            listOf(
                Call.GetQueue(0, QueueSyncService.PAGE_SIZE, true),
                Call.GetQueue(1, QueueSyncService.PAGE_SIZE, true)
            ),
            fake.callsOf<Call.GetQueue>()
        )
        val applied = slot<QueueSnapshot>()
        verify(exactly = 1) { playerModel.applyRemoteQueue(capture(applied), isNull()) }
        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), any()) }
        val snapshot = applied.captured
        assertEquals(remote.map { it.queueId }, snapshot.original.map { it.queueId })
        assertEquals(remote.reversed().map { it.queueId }, snapshot.active.map { it.queueId })
        assertEquals(remote.map { it.songId }, snapshot.original.map(::songIdOf))
        assertEquals(2, snapshot.currentIndex)
        assertTrue(snapshot.shuffle)
        assertEquals(RepeatMode.ALL, snapshot.repeat)
        assertEquals(info.sourceId, snapshot.sourceId)
        assertEquals(listOf(Call.AckSynced(info.version)), fake.callsOf<Call.AckSynced>())
        assertEquals(info.version, service.syncedVersionFlow.value)
        assertEquals(info.version, settings.get(SettingKey.QueueSyncVersion, 0L))
    }

    @Test
    fun foreignNewerQueueWhilePlayingWaitsForTheUserAndCanBeAppliedOrDismissed() = runTest(dispatcher) {
        startSynced()
        playingFlow.value = true

        val first = foreignWrite(remoteItems(4))
        scheduler.runCurrent()

        assertEquals(first, service.pendingRemote.value)
        assertEquals(first.modifiedByDeviceName, service.status.value.behindDevice)
        assertEquals(emptyList(), fake.callsOf<Call.GetQueue>())
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }

        service.applyPendingRemote()
        scheduler.runCurrent()

        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), isNull()) }
        assertNull(service.pendingRemote.value)
        assertNull(service.status.value.behindDevice)
        assertEquals(first.version, service.syncedVersionFlow.value)

        val second = foreignWrite(remoteItems(2, 20_000L))
        scheduler.runCurrent()
        assertEquals(second, service.pendingRemote.value)

        service.dismissPendingRemote()
        scheduler.runCurrent()

        assertNull(service.pendingRemote.value)
        assertNull(service.status.value.behindDevice)
        assertEquals(first.version, service.syncedVersionFlow.value)
        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun foreignNewerQueueWhilePlayingAndControlledIsPulledKeepingTheCurrentEntry() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        playingFlow.value = true
        controlledFlow.value = true

        val info = foreignWrite(remoteItems(4))
        scheduler.runCurrent()

        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), entries[1].queueId) }
        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), any()) }
        assertNull(service.pendingRemote.value)
        assertEquals(info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun versionMovingDuringPagingRefetchesAndAppliesTheNewerQueue() = runTest(dispatcher) {
        startSynced()
        val newer = remoteItems(3, 20_000L)
        var moved = false
        fake.onCall = { call ->
            if (call is Call.GetQueue && !moved) {
                moved = true
                foreignWrite(newer, notify = false)
            }
        }

        foreignWrite(remoteItems(2))
        scheduler.runCurrent()

        assertEquals(2, fake.callsOf<Call.GetQueue>().size)
        val applied = slot<QueueSnapshot>()
        verify(exactly = 1) { playerModel.applyRemoteQueue(capture(applied), any()) }
        assertEquals(newer.map { it.queueId }, applied.captured.original.map { it.queueId })
        assertEquals(listOf(Call.AckSynced(SYNCED + 2)), fake.callsOf<Call.AckSynced>())
        assertEquals(SYNCED + 2, service.syncedVersionFlow.value)
    }

    @Test
    fun versionMovingOnEveryPullAttemptAppliesNothing() = runTest(dispatcher) {
        startSynced()
        var round = 0L
        fake.onCall = { call ->
            if (call is Call.GetQueue) {
                round++
                foreignWrite(remoteItems(2, 20_000L * round), notify = false)
            }
        }

        foreignWrite(remoteItems(2))
        scheduler.runCurrent()

        assertEquals(QueueSyncService.PULL_ATTEMPTS, fake.callsOf<Call.GetQueue>().size)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
        assertEquals(emptyList(), fake.callsOf<Call.AckSynced>())
        assertEquals(SYNCED, service.syncedVersionFlow.value)
    }

    @Test
    fun songsAreRequestedUpToTheIncludeLimitAndCached() = runTest(dispatcher) {
        startSynced()
        val song = testSong(1)
        val atLimit = remoteItems(QueueSyncService.SONG_INCLUDE_LIMIT).mapIndexed { index, item ->
            if (index == 0) item.copy(songId = song.id, userAdded = true, song = song) else item
        }

        foreignWrite(atLimit)
        scheduler.runCurrent()

        val limited = fake.callsOf<Call.GetQueue>()
        assertTrue(limited.isNotEmpty())
        assertTrue(limited.all { it.includeSongs })
        assertEquals(song, songCache.get(song.id))

        fake.calls.clear()
        foreignWrite(remoteItems(QueueSyncService.SONG_INCLUDE_LIMIT + 1, 50_000L))
        scheduler.runCurrent()

        val unlimited = fake.callsOf<Call.GetQueue>()
        assertTrue(unlimited.isNotEmpty())
        assertTrue(unlimited.none { it.includeSongs })
        verify(exactly = 2) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun pulledUserAddedItemWithASongBecomesAnExplicitEntry() = runTest(dispatcher) {
        startSynced()
        val added = testSong(1)
        val sourced = testSong(2)
        val unknown = randomPlatformUUID()
        val remote = listOf(
            QueueItem(songId = added.id, queueId = 10_000L, position = 0, userAdded = true, song = added),
            QueueItem(songId = unknown, queueId = 10_001L, position = 1, userAdded = true),
            QueueItem(songId = sourced.id, queueId = 10_002L, position = 2, userAdded = false, song = sourced)
        )

        foreignWrite(remote)
        scheduler.runCurrent()

        val expected = listOf(
            QueueEntry.Explicit(added, 10_000L),
            QueueEntry.FromSource(unknown, 10_001L),
            QueueEntry.FromSource(sourced.id, 10_002L)
        )
        assertEquals(1, appliedQueues.size)
        assertEquals<List<QueueEntry>>(expected, appliedQueues.single().snapshot.original)
        assertEquals<List<QueueEntry>>(expected, appliedQueues.single().snapshot.active)
    }

    @Test
    fun conflictingInsertIsRebasedOntoTheServerQueue() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        val events = mutableListOf<QueueSyncEvent>()
        backgroundScope.launch { service.events.toList(events) }
        scheduler.runCurrent()

        val remote = remoteItems(4)
        val info = foreignWrite(remote, notify = false)
        val inserted = QueueEntry.FromSource(randomPlatformUUID(), 301L)
        every { playerModel.insertEntries(any(), any()) } answers {
            emit(QueueChange.Inserted(firstArg(), secondArg(), 3))
        }

        emit(QueueChange.Inserted(Anchor.AFTER_CURRENT, listOf(inserted), 2))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        val item = QueueItem(songId = inserted.songId, queueId = 301L, position = 0, shuffledPosition = null, userAdded = false)
        assertEquals(
            listOf(
                Call.Insert(SYNCED, 2, listOf(item), false),
                Call.Insert(info.version, 3, listOf(item), false)
            ),
            fake.callsOf<Call.Insert>()
        )
        assertEquals(listOf(remote.map { it.queueId }), appliedQueueIds())
        assertEquals(listOf<Long?>(entries[1].queueId), appliedQueues.map { it.keepCurrentQueueId })
        assertEquals(listOf<QueueSyncEvent>(QueueSyncEvent.Merged(OTHER_DEVICE)), events)
        assertNull(service.pendingConflict.value)
        assertEquals(remote.take(3).map { it.queueId } + 301L + remote.drop(3).map { it.queueId }, fake.activeQueueIds)
        assertEquals(info.version + 1, fake.info.version)
        assertEquals(info.version + 1, service.syncedVersionFlow.value)
    }

    @Test
    fun thirdConsecutiveConflictAsksTheUserAndKeepingLocalForcesAnUpload() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        raiseConflictPrompt(entries[1].queueId)
        val writesBefore = fake.callsOf<Call.Write>()
        val syncedBefore = service.syncedVersionFlow.value

        val kept = entries.take(2)
        localQueue(kept, 1)
        emit(QueueChange.Removed(listOf(entries[2].queueId)))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(emptyList(), fake.callsOf<Call.Remove>())
        assertEquals(emptyList(), fake.callsOf<Call.BeginUpload>())
        assertEquals(writesBefore, fake.callsOf<Call.Write>())

        service.resolveConflict(true)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        val uploadId = assertNotNull(fake.lastUploadId)
        assertEquals(
            listOf(
                Call.BeginUpload(syncedBefore, true),
                Call.UploadPage(uploadId, itemsOf(kept)),
                Call.CommitUpload(
                    uploadId = uploadId,
                    meta = QueueMeta(currentIndex = 1, isShuffled = false, repeatMode = RepeatMode.OFF, sourceId = null),
                    requestId = null
                )
            ),
            fake.callsOf<Call.Write>().drop(writesBefore.size)
        )
        assertEquals(kept.map { it.queueId }, fake.activeQueueIds)
        assertEquals(fake.info.version, service.syncedVersionFlow.value)
        assertEquals(QueueSyncService.MAX_CONFLICTS - 1, appliedQueues.size)
    }

    @Test
    fun droppingLocalAfterTheConflictPromptPullsTheServerQueue() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        raiseConflictPrompt(entries[1].queueId)
        val writesBefore = fake.callsOf<Call.Write>()

        service.resolveConflict(false)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertEquals(emptyList(), fake.callsOf<Call.BeginUpload>())
        assertEquals(writesBefore, fake.callsOf<Call.Write>())
        assertEquals(foreignQueues.map { it.queueIds }, appliedQueueIds())
        assertEquals(foreignQueues.last().info, fake.info)
        assertEquals(fake.info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun changeQueuedDuringTheConflictPromptIsDroppedWithTheLocalQueue() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        raiseConflictPrompt(entries[1].queueId)
        val writesBefore = fake.callsOf<Call.Write>()

        emit(insertChange(900L))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(writesBefore, fake.callsOf<Call.Write>())

        service.resolveConflict(false)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertEquals(writesBefore, fake.callsOf<Call.Write>())
        assertEquals(foreignQueues.map { it.queueIds }, appliedQueueIds())
        assertEquals(appliedQueueIds().last(), fake.activeQueueIds)
        assertEquals(foreignQueues.last().info, fake.info)
        assertEquals(fake.info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun conflictsKeepRebasingWithoutAPromptWhileControlled() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        controlledFlow.value = true
        val rounds = QueueSyncService.MAX_CONFLICTS + 1
        conflictNextInserts(rounds)

        repeat(rounds) { round ->
            emit(insertChange(200L + round))
            scheduler.runCurrent()
        }

        assertNull(service.pendingConflict.value)
        assertEquals(rounds, foreignQueues.size)
        assertEquals(
            listOf(SYNCED) + foreignQueues.dropLast(1).map { it.info.version },
            fake.callsOf<Call.Insert>().map { it.baseVersion }
        )
        assertEquals(foreignQueues.map { it.queueIds }, appliedQueueIds())
        assertEquals(List<Long?>(rounds) { entries[1].queueId }, appliedQueues.map { it.keepCurrentQueueId })
        assertEquals(foreignQueues.last().info.version, service.syncedVersionFlow.value)
        verify(exactly = rounds) { playerModel.insertEntries(Anchor.END, any()) }
    }

    @Test
    fun acceptedWriteBetweenConflictsResetsTheStreak() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        val beforePrompt = QueueSyncService.MAX_CONFLICTS - 1

        conflictNextInserts(beforePrompt)
        repeat(beforePrompt) { round ->
            emit(insertChange(200L + round))
            scheduler.runCurrent()
        }
        val beforeAccepted = foreignQueues.last().info.version
        assertEquals(beforeAccepted, service.syncedVersionFlow.value)
        emit(insertChange(300L))
        scheduler.runCurrent()
        assertEquals(beforeAccepted + 1, service.syncedVersionFlow.value)
        assertEquals(beforeAccepted + 1, fake.info.version)

        conflictNextInserts(beforePrompt)
        repeat(beforePrompt) { round ->
            emit(insertChange(400L + round))
            scheduler.runCurrent()
        }
        assertNull(service.pendingConflict.value)
        assertEquals(beforePrompt * 2, foreignQueues.size)
        assertEquals(foreignQueues.map { it.queueIds }, appliedQueueIds())
        assertEquals(List<Long?>(beforePrompt * 2) { entries[1].queueId }, appliedQueues.map { it.keepCurrentQueueId })
        assertEquals(foreignQueues.last().info.version, service.syncedVersionFlow.value)

        conflictNextInserts(1)
        emit(insertChange(500L))
        scheduler.runCurrent()
        assertEquals(foreignQueues.last().info, service.pendingConflict.value)
        assertEquals(beforePrompt * 2, appliedQueues.size)
        val conflicted = foreignQueues.dropLast(1).map { it.info.version }
        assertEquals(
            listOf(SYNCED) + conflicted.take(beforePrompt) + (beforeAccepted + 1) + conflicted.drop(beforePrompt),
            fake.callsOf<Call.Insert>().map { it.baseVersion }
        )
    }

    @Test
    fun conflictWhoseServerQueueKeepsMovingDoesNotLoseTheChange() = runTest(dispatcher) {
        val entries = startSynced()
        val inserted = QueueEntry.FromSource(randomPlatformUUID(), 200L)
        localQueue(entries + inserted)
        var round = 0L
        fake.onCall = { call ->
            if (call is Call.Insert || call is Call.GetQueue) {
                round++
                foreignWrite(remoteItems(2, 20_000L * round), notify = false)
            }
        }

        emit(QueueChange.Inserted(Anchor.END, listOf(inserted), entries.size))
        scheduler.runCurrent()

        assertEquals(QueueSyncService.PULL_ATTEMPTS, fake.callsOf<Call.GetQueue>().size)
        assertEquals(emptyList(), appliedQueues)
        assertEquals(SYNCED, service.syncedVersionFlow.value)
        assertTrue(service.status.value.uploadPending || service.pendingConflict.value != null)
    }

    @Test
    fun failedWriteMarksTheQueueDirtyAndTheNextChangeForcesAFullUpload() = runTest(dispatcher) {
        val entries = startSynced()
        fake.onCall = { call -> if (call is Call.Insert) throw IOException("insert failed") }

        emit(insertChange(200L))
        scheduler.runCurrent()

        assertTrue(service.status.value.uploadPending)
        assertTrue(settings.get(SettingKey.QueueSyncDirty, false))
        assertEquals(SYNCED, service.syncedVersionFlow.value)

        fake.onCall = {}
        emit(QueueChange.Removed(listOf(entries[2].queueId)))
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertEquals(emptyList(), fake.callsOf<Call.Remove>())
        assertFalse(service.status.value.uploadPending)
        assertFalse(settings.get(SettingKey.QueueSyncDirty, true))
        assertEquals(SYNCED + 1, service.syncedVersionFlow.value)
    }

    @Test
    fun replacedQueueIsUploadedInChunksWithPositionsAndMeta() = runTest(dispatcher) {
        val entries = startSynced(localEntries(QueueSyncService.UPLOAD_CHUNK + 1))
        localSnapshot = QueueSnapshot(
            original = entries,
            active = entries.reversed(),
            currentIndex = 4,
            shuffle = true,
            repeat = RepeatMode.ALL,
            sourceId = "source"
        )

        emit(QueueChange.Replaced)
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED, false)), fake.callsOf<Call.BeginUpload>())
        val shuffledPages = fake.callsOf<Call.UploadPage>()
        assertEquals(listOf(QueueSyncService.UPLOAD_CHUNK, 1), shuffledPages.map { it.items.size })
        assertTrue(shuffledPages.all { it.uploadId == fake.lastUploadId })
        val shuffledItems = shuffledPages.flatMap { it.items }
        assertEquals(entries.map { it.queueId }, shuffledItems.map { it.queueId })
        assertEquals(entries.map(::songIdOf), shuffledItems.map { it.songId })
        assertEquals(entries.map { it is QueueEntry.Explicit }, shuffledItems.map { it.userAdded })
        assertEquals(entries.indices.toList(), shuffledItems.map { it.position })
        assertEquals(entries.indices.map { entries.size - 1 - it }, shuffledItems.map { it.shuffledPosition })
        assertEquals(
            listOf(
                Call.CommitUpload(
                    uploadId = assertNotNull(fake.lastUploadId),
                    meta = QueueMeta(currentIndex = 4, isShuffled = true, repeatMode = RepeatMode.ALL, sourceId = "source"),
                    requestId = null
                )
            ),
            fake.callsOf<Call.CommitUpload>()
        )
        assertEquals(SYNCED + 1, service.syncedVersionFlow.value)

        fake.calls.clear()
        localSnapshot = localSnapshot.copy(active = entries, shuffle = false)
        emit(QueueChange.Replaced)
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED + 1, false)), fake.callsOf<Call.BeginUpload>())
        val plainItems = fake.callsOf<Call.UploadPage>().flatMap { it.items }
        assertEquals(entries.indices.toList(), plainItems.map { it.position })
        assertTrue(plainItems.all { it.shuffledPosition == null })
        assertEquals(listOf(false), fake.callsOf<Call.CommitUpload>().map { it.meta.isShuffled })
    }

    @Test
    fun replacedQueueThatConflictsAtBeginIsUploadedAgainWithForce() = runTest(dispatcher) {
        startSynced()
        foreignWrite(remoteItems(2), notify = false)

        emit(QueueChange.Replaced)
        scheduler.runCurrent()

        assertEquals(
            listOf(Call.BeginUpload(SYNCED, false), Call.BeginUpload(SYNCED, true)),
            fake.callsOf<Call.BeginUpload>()
        )
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertEquals(SYNCED + 2, service.syncedVersionFlow.value)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun failingUploadPageCancelsTheUploadAndMarksTheQueueDirty() = runTest(dispatcher) {
        startSynced()
        fake.onCall = { call -> if (call is Call.UploadPage) throw IOException("page failed") }

        emit(QueueChange.Replaced)
        scheduler.runCurrent()

        assertEquals(
            listOf(Call.CancelUpload(assertNotNull(fake.lastUploadId))),
            fake.callsOf<Call.CancelUpload>()
        )
        assertEquals(emptyList(), fake.callsOf<Call.CommitUpload>())
        assertTrue(service.status.value.uploadPending)
        assertTrue(settings.get(SettingKey.QueueSyncDirty, false))
        assertEquals(SYNCED, service.syncedVersionFlow.value)
    }

    @Test
    fun changesQueuedBehindAReplacedAreCoveredByTheUploadOnly() = runTest(dispatcher) {
        val entries = startSynced()

        emit(QueueChange.Replaced)
        emit(insertChange(200L))
        emit(QueueChange.Removed(listOf(entries[2].queueId)))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertEquals(emptyList(), fake.callsOf<Call.Insert>())
        assertEquals(emptyList(), fake.callsOf<Call.Remove>())
        assertEquals(SYNCED + 1, service.syncedVersionFlow.value)
    }

    @Test
    fun endlessSourceSendsNoChangesAndRejectsAnUploadRequest() = runTest(dispatcher) {
        val entries = startSynced()
        sourceFlow.value = PlaybackSource.Radio(randomPlatformUUID())

        emit(insertChange(200L))
        emit(QueueChange.CurrentChanged(entries[1].queueId, 1))
        emit(QueueChange.Replaced)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(emptyList(), fake.callsOf<Call.Write>())

        val request = uploadRequest()
        assertTrue(requestFlow.tryEmit(request))
        scheduler.runCurrent()

        coVerify(exactly = 1) { clientRequestService.complete(request.id, ClientRequestStatus.REJECTED) }
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
    }

    @Test
    fun uploadRequestWithAnEmptyQueueIsRejected() = runTest(dispatcher) {
        startSynced(emptyList())

        val request = uploadRequest()
        assertTrue(requestFlow.tryEmit(request))
        scheduler.runCurrent()

        coVerify(exactly = 1) { clientRequestService.complete(request.id, ClientRequestStatus.REJECTED) }
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
    }

    @Test
    fun uploadRequestWithContentCommitsAForcedUploadWithItsRequestId() = runTest(dispatcher) {
        startSynced()

        val request = uploadRequest()
        assertTrue(requestFlow.tryEmit(request))
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(listOf<PlatformUUID?>(request.id), fake.callsOf<Call.CommitUpload>().map { it.requestId })
        coVerify(exactly = 0) { clientRequestService.complete(any(), any()) }
    }

    @Test
    fun uploadRequestWhoseUploadFailsIsRejected() = runTest(dispatcher) {
        startSynced()
        fake.onCall = { call -> if (call is Call.UploadPage) throw IOException("page failed") }

        val request = uploadRequest()
        assertTrue(requestFlow.tryEmit(request))
        scheduler.runCurrent()

        assertEquals(emptyList(), fake.callsOf<Call.CommitUpload>())
        coVerify(exactly = 1) { clientRequestService.complete(request.id, ClientRequestStatus.REJECTED) }
    }

    @Test
    fun firstEnableWithContentOnBothSidesAsksAndLoadingRemotePulls() = runTest(dispatcher) {
        localQueue(localEntries(3))
        val info = foreignWrite(remoteItems(4), notify = false)

        service.start()
        scheduler.runCurrent()

        assertEquals(info, service.pendingEnableChoice.value)
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }

        service.resolveEnableChoice(true)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), isNull()) }
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        assertEquals(info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun firstEnableChoiceToKeepLocalForcesAnUpload() = runTest(dispatcher) {
        localQueue(localEntries(3))
        val info = foreignWrite(remoteItems(4), notify = false)
        service.start()
        scheduler.runCurrent()
        assertEquals(info, service.pendingEnableChoice.value)

        service.resolveEnableChoice(false)
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)
        assertEquals(listOf(Call.BeginUpload(0L, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
        assertEquals(info.version + 1, service.syncedVersionFlow.value)
    }

    @Test
    fun cancellingTheFirstEnableChoiceTurnsQueueSyncOff() = runTest(dispatcher) {
        localQueue(localEntries(3))
        val info = foreignWrite(remoteItems(4), notify = false)
        service.start()
        scheduler.runCurrent()
        assertEquals(info, service.pendingEnableChoice.value)

        service.cancelEnableChoice()
        scheduler.runCurrent()

        assertFalse(Config.isQueueSyncEnabled.value)
        assertNull(service.pendingEnableChoice.value)
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun persistedDirtyFlagForcesAnUploadAtSessionStart() = runTest(dispatcher) {
        prepareSynced()
        settings.put(SettingKey.QueueSyncDirty, true)

        service.start()
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertFalse(settings.get(SettingKey.QueueSyncDirty, true))
        assertFalse(service.status.value.uploadPending)
    }

    @Test
    fun serverWithoutAQueueReceivesTheLocalQueueUnforced() = runTest(dispatcher) {
        localQueue(localEntries(3))

        service.start()
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(0L, false)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertEquals(1L, service.syncedVersionFlow.value)
    }

    @Test
    fun neverWrittenEmptyServerQueueAtANewerVersionIsOverwrittenByTheLocalQueue() = runTest(dispatcher) {
        localQueue(localEntries(3))
        fake.info = fake.info.copy(version = 3L)

        service.start()
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(0L, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
        assertEquals(4L, service.syncedVersionFlow.value)
    }

    @Test
    fun reconnectAfterAnOfflineChangeForcesAnUpload() = runTest(dispatcher) {
        startSynced()

        reachableFlow.value = false
        scheduler.runCurrent()
        emit(insertChange(200L))
        scheduler.runCurrent()
        assertEquals(emptyList(), fake.callsOf<Call.Write>())

        reachableFlow.value = true
        scheduler.runCurrent()

        assertEquals(listOf(Call.BeginUpload(SYNCED, true)), fake.callsOf<Call.BeginUpload>())
        assertEquals(1, fake.callsOf<Call.CommitUpload>().size)
        assertEquals(emptyList(), fake.callsOf<Call.Insert>())
        assertEquals(
            listOf(Call.SetSyncEnabled(true, identity.deviceName()), Call.SetSyncEnabled(true, identity.deviceName())),
            fake.callsOf<Call.SetSyncEnabled>()
        )
    }

    @Test
    fun reconnectWithoutAChangeOnlyReadsTheQueueInfo() = runTest(dispatcher) {
        startSynced()

        reachableFlow.value = false
        scheduler.runCurrent()
        fake.calls.clear()
        reachableFlow.value = true
        scheduler.runCurrent()

        assertEquals(1, fake.callsOf<Call.GetQueueInfo>().size)
        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        assertEquals(emptyList(), fake.callsOf<Call.GetQueue>())
        assertEquals(listOf(Call.SetSyncEnabled(true, identity.deviceName())), fake.callsOf<Call.SetSyncEnabled>())
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun disablingTellsTheServerAndClearsTheSyncState() = runTest(dispatcher) {
        startSynced()
        playingFlow.value = true
        fake.onCall = { call -> if (call is Call.Insert) throw IOException("insert failed") }
        emit(insertChange(200L))
        scheduler.runCurrent()
        fake.onCall = {}
        foreignWrite(remoteItems(2))
        scheduler.runCurrent()
        assertNotNull(service.pendingRemote.value)
        assertTrue(settings.get(SettingKey.QueueSyncDirty, false))

        Config.setIsQueueSyncEnabled(false)
        scheduler.runCurrent()

        assertEquals(
            listOf(Call.SetSyncEnabled(true, identity.deviceName()), Call.SetSyncEnabled(false, identity.deviceName())),
            fake.callsOf<Call.SetSyncEnabled>()
        )
        assertEquals(0L, service.syncedVersionFlow.value)
        assertEquals(0L, settings.get(SettingKey.QueueSyncVersion, SYNCED))
        assertFalse(settings.get(SettingKey.QueueSyncDirty, true))
        assertNull(service.pendingRemote.value)
        assertFalse(service.status.value.uploadPending)
        assertNull(service.status.value.behindDevice)
    }

    @Test
    fun disablingWhileTheConflictPromptIsOpenClosesItAndALateAnswerWritesNothing() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        raiseConflictPrompt(entries[1].queueId)
        val writesBefore = fake.callsOf<Call.Write>()

        Config.setIsQueueSyncEnabled(false)
        scheduler.runCurrent()

        assertNull(service.pendingConflict.value)
        assertEquals(0L, service.syncedVersionFlow.value)

        service.resolveConflict(true)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(writesBefore, fake.callsOf<Call.Write>())
        assertEquals(QueueSyncService.MAX_CONFLICTS - 1, appliedQueues.size)
    }

    @Test
    fun logoutWhileTheFirstEnableChoiceIsOpenClosesItAndALateAnswerWritesNothing() = runTest(dispatcher) {
        localQueue(localEntries(3))
        val info = foreignWrite(remoteItems(4), notify = false)
        service.start()
        scheduler.runCurrent()
        assertEquals(info, service.pendingEnableChoice.value)

        connectionFlow.value = ConnectionState.LoginRequired
        scheduler.runCurrent()

        assertNull(service.pendingEnableChoice.value)

        service.resolveEnableChoice(false)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        assertEquals(emptyList(), fake.callsOf<Call.Write>())
        assertEquals(emptyList(), appliedQueues)
        assertTrue(Config.isQueueSyncEnabled.value)
    }

    @Test
    fun logoutClearsTheSyncStateWithoutTellingTheServer() = runTest(dispatcher) {
        startSynced()

        connectionFlow.value = ConnectionState.LoginRequired
        scheduler.runCurrent()

        assertEquals(listOf(Call.SetSyncEnabled(true, identity.deviceName())), fake.callsOf<Call.SetSyncEnabled>())
        assertEquals(0L, service.syncedVersionFlow.value)
        assertEquals(0L, settings.get(SettingKey.QueueSyncVersion, SYNCED))
        assertFalse(settings.get(SettingKey.QueueSyncDirty, true))
    }

    @Test
    fun failedOrCompletedQueueStreamIsResubscribedAfterTheRetryDelay() = runTest(dispatcher) {
        prepareSynced()
        fake.observeFlows.addLast(flow { throw IOException("stream failed") })
        fake.observeFlows.addLast(emptyFlow())

        service.start()
        scheduler.runCurrent()
        assertEquals(1, fake.callsOf<Call.ObserveQueue>().size)

        scheduler.advanceTimeBy(4_999)
        scheduler.runCurrent()
        assertEquals(1, fake.callsOf<Call.ObserveQueue>().size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(2, fake.callsOf<Call.ObserveQueue>().size)

        scheduler.advanceTimeBy(4_999)
        scheduler.runCurrent()
        assertEquals(2, fake.callsOf<Call.ObserveQueue>().size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(3, fake.callsOf<Call.ObserveQueue>().size)

        fake.updates.tryEmit(fake.info.copy(version = SYNCED + 2, modifiedBySessionId = ownSession))
        scheduler.runCurrent()
        assertEquals(SYNCED + 2, service.syncedVersionFlow.value)
    }

    @Test
    fun ensureVersionReturnsAtOnceWhenTheVersionIsReached() = runTest(dispatcher) {
        startSynced()
        fake.calls.clear()
        val startedAt = scheduler.currentTime

        assertTrue(service.ensureVersion(SYNCED, 1.seconds))

        assertEquals(emptyList(), fake.calls)
        assertEquals(startedAt, scheduler.currentTime)
    }

    @Test
    fun ensureVersionIsFalseWithoutAnActiveSession() = runTest(dispatcher) {
        reachableFlow.value = false
        prepareSynced()
        service.start()
        scheduler.runCurrent()
        val startedAt = scheduler.currentTime

        assertFalse(service.ensureVersion(SYNCED + 1, 1.seconds))

        assertEquals(emptyList(), fake.calls)
        assertEquals(startedAt, scheduler.currentTime)
    }

    @Test
    fun ensureVersionPullsWhenTheServerIsAtTheWantedVersion() = runTest(dispatcher) {
        val entries = startSynced(currentIndex = 1)
        playingFlow.value = true
        val info = foreignWrite(remoteItems(4), notify = false)

        assertTrue(service.ensureVersion(info.version, 1.seconds))

        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), entries[1].queueId) }
        verify(exactly = 1) { playerModel.applyRemoteQueue(any(), any()) }
        assertEquals(info.version, service.syncedVersionFlow.value)
    }

    @Test
    fun ensureVersionIsFalseWhenTheVersionIsNotReachedInTime() = runTest(dispatcher) {
        startSynced()
        val startedAt = scheduler.currentTime

        assertFalse(service.ensureVersion(SYNCED + 1, 1.seconds))

        assertEquals(1_000L, scheduler.currentTime - startedAt)
        assertEquals(SYNCED, service.syncedVersionFlow.value)
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun awaitFlushedReturnsTheVersionOnlyAfterThePendingChangeIsPushed() = runTest(dispatcher) {
        startSynced()
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { call -> if (call is Call.Insert) gate.await() }

        emit(insertChange(200L))
        val flushed = async { service.awaitFlushed(5.seconds) }
        scheduler.runCurrent()

        assertEquals(1, fake.callsOf<Call.Insert>().size)
        assertFalse(flushed.isCompleted)

        gate.complete(Unit)
        scheduler.runCurrent()

        assertTrue(flushed.isCompleted)
        assertEquals(SYNCED + 1, flushed.await())
    }

    @Test
    fun awaitFlushedIsNullWithoutAnActiveSession() = runTest(dispatcher) {
        reachableFlow.value = false
        prepareSynced()
        service.start()
        scheduler.runCurrent()
        val startedAt = scheduler.currentTime

        assertNull(service.awaitFlushed(1.seconds))

        assertEquals(startedAt, scheduler.currentTime)
    }

    @Test
    fun awaitFlushedIsNullWhenThePendingChangeIsNotPushedInTime() = runTest(dispatcher) {
        startSynced()
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { call -> if (call is Call.Insert) gate.await() }
        val startedAt = scheduler.currentTime

        emit(insertChange(200L))

        assertNull(service.awaitFlushed(1.seconds))
        assertEquals(1_000L, scheduler.currentTime - startedAt)
    }

    @Test
    fun requestedQueueThatCompletesWithANewerVersionIsPulledAndDevicesAreRefreshed() = runTest(dispatcher) {
        startSynced()
        val remote = remoteItems(4)
        fake.devices = listOf(
            QueueSyncDevice(
                sessionId = otherSession,
                deviceName = OTHER_DEVICE,
                enabled = true,
                lastSyncedVersion = SYNCED + 1,
                lastSyncAt = 0L,
                lastActive = 0L,
                isCurrent = false
            )
        )
        fake.onCall = { call -> if (call is Call.RequestUploadFrom) foreignWrite(remote, notify = false) }

        service.requestQueueFrom(otherSession)
        scheduler.runCurrent()

        assertEquals(listOf(Call.RequestUploadFrom(otherSession)), fake.callsOf<Call.RequestUploadFrom>())
        val applied = slot<QueueSnapshot>()
        verify(exactly = 1) { playerModel.applyRemoteQueue(capture(applied), any()) }
        assertEquals(remote.map { it.queueId }, applied.captured.original.map { it.queueId })
        assertEquals(mapOf(otherSession to ClientRequestStatus.COMPLETED), service.requestStatuses.value)
        assertEquals(SYNCED + 1, service.syncedVersionFlow.value)
        assertNull(service.busySessionId.value)
        assertFalse(service.isBusy.value)
        assertEquals(1, fake.callsOf<Call.GetSyncDevices>().size)
        assertEquals(fake.devices, service.devices.value)
    }

    @Test
    fun requestStatusesAreStoredPerSession() = runTest(dispatcher) {
        startSynced()
        val third = randomPlatformUUID()

        fake.requestStatus = ClientRequestStatus.REJECTED
        service.requestQueueFrom(otherSession)
        scheduler.runCurrent()
        fake.requestStatus = ClientRequestStatus.TIMED_OUT
        service.requestQueueFrom(third)
        scheduler.runCurrent()

        assertEquals(
            mapOf(otherSession to ClientRequestStatus.REJECTED, third to ClientRequestStatus.TIMED_OUT),
            service.requestStatuses.value
        )
        verify(exactly = 0) { playerModel.applyRemoteQueue(any(), any()) }
    }

    @Test
    fun failingQueueRequestIsStoredAsUnreachable() = runTest(dispatcher) {
        startSynced()
        fake.onCall = { call -> if (call is Call.RequestUploadFrom) throw IOException("request failed") }

        service.requestQueueFrom(otherSession)
        scheduler.runCurrent()

        assertEquals(mapOf(otherSession to ClientRequestStatus.UNREACHABLE), service.requestStatuses.value)
        assertNull(service.busySessionId.value)
        assertFalse(service.isBusy.value)
        assertEquals(1, fake.callsOf<Call.GetSyncDevices>().size)
    }

    @Test
    fun queueRequestWhileAnotherIsRunningIsIgnored() = runTest(dispatcher) {
        startSynced()
        val third = randomPlatformUUID()
        val gate = CompletableDeferred<Unit>()
        fake.onCall = { call -> if (call is Call.RequestUploadFrom) gate.await() }

        service.requestQueueFrom(otherSession)
        scheduler.runCurrent()
        assertEquals(otherSession, service.busySessionId.value)

        service.requestQueueFrom(third)
        scheduler.runCurrent()
        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(listOf(Call.RequestUploadFrom(otherSession)), fake.callsOf<Call.RequestUploadFrom>())
        assertEquals(setOf(otherSession), service.requestStatuses.value.keys)
        assertNull(service.busySessionId.value)
    }

    @Test
    fun deviceNameChangeIsAnnouncedToTheServer() = runTest(dispatcher) {
        startSynced()
        val before = identity.deviceName()

        Config.setQueueSyncDeviceName("renamed")
        scheduler.runCurrent()

        val after = identity.deviceName()
        assertTrue(before != after)
        assertEquals(
            listOf(Call.SetSyncEnabled(true, before), Call.SetSyncEnabled(true, after)),
            fake.callsOf<Call.SetSyncEnabled>()
        )
    }
}
