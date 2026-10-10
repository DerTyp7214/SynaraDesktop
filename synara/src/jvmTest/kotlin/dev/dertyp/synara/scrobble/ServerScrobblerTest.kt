package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.PlaybackReport
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IScrobbleService
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.RawScrobbleQueueEntry
import dev.dertyp.synara.db.ScrobbleQueueEntry
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.ServerClock
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemoryScrobbleQueueRepository : ScrobbleQueueRepository {
    data class RawRow(val entry: RawScrobbleQueueEntry, val songId: String)

    private var nextId = 1L
    val rawRows = mutableListOf<RawRow>()
    val songRows = mutableListOf<ScrobbleQueueEntry>()

    override suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long, target: String) {
        songRows += ScrobbleQueueEntry(nextId++, userId, song, timestamp, target)
    }

    override suspend fun insertRaw(userId: PlatformUUID, songId: String, payload: String, timestamp: Long, target: String) {
        rawRows += RawRow(RawScrobbleQueueEntry(nextId++, userId, payload, timestamp, target), songId)
    }

    override suspend fun getAll(userId: PlatformUUID, target: String): List<ScrobbleQueueEntry> =
        songRows.filter { it.userId == userId && it.target == target }.sortedBy { it.timestamp }

    override suspend fun getAllRaw(userId: PlatformUUID, target: String): List<RawScrobbleQueueEntry> =
        rawRows.map { it.entry }.filter { it.userId == userId && it.target == target }.sortedBy { it.timestamp }

    override suspend fun delete(id: Long) {
        rawRows.removeAll { it.entry.id == id }
        songRows.removeAll { it.id == id }
    }

    override suspend fun getCount(userId: PlatformUUID, target: String): Long =
        (getAll(userId, target).size + getAllRaw(userId, target).size).toLong()

    override suspend fun peek(userId: PlatformUUID, target: String): ScrobbleQueueEntry? =
        getAll(userId, target).firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServerScrobblerTest {
    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(User(id = userId, username = "listener"))
    private val reachableFlow = MutableStateFlow(true)
    private val playingFlow = MutableStateFlow(false)
    private val positionFlow = MutableStateFlow(0L)
    private val seekFlow = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private var playerSongId: PlatformUUID? = null

    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val playerModel = mockk<PlayerModel>()
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val scrobbleService = mockk<IScrobbleService>()
    private val repository = InMemoryScrobbleQueueRepository()
    private val serverClock = ServerClock()
    private val payloadJson = Json(AppJson) { ignoreUnknownKeys = true }

    private val sent = mutableListOf<ScrobbleRequest>()
    private val reports = mutableListOf<PlaybackReport>()
    private var clears = 0

    private lateinit var scrobbler: ServerScrobbler

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        every { scrobblerService.triggeredSong } returns MutableStateFlow<TriggeredListen?>(null)
        every { scrobblerService.newSong } returns MutableStateFlow<UserSong?>(null)
        every { scrobblerService.resumed } returns MutableSharedFlow<UserSong>()
        every { scrobblerService.resetTrigger } returns MutableSharedFlow<Unit>()
        every { scrobblerService.listenEnded } returns MutableSharedFlow<ListenEnded>()
        every { playerModel.isPlaying } returns playingFlow
        every { playerModel.currentPosition } returns positionFlow
        every { playerModel.seekEvents } returns seekFlow
        every { playerModel.currentSongId } answers { playerSongId }
        every { rpcServiceManager.isServerReachable } returns reachableFlow
        coEvery { scrobbleService.listened(capture(sent)) } just Runs
        coEvery { scrobbleService.reportPlayback(capture(reports)) } answers { currentTimeMillis() }
        coEvery { scrobbleService.clearNowPlaying() } answers { clears++ }

        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
            }
        )
        Config.setIsServerScrobblingEnabled(true)
        scrobbler = ServerScrobbler(
            scrobbleService = scrobbleService,
            scrobbleQueue = ScrobbleQueue(repository),
            rpcServiceManager = rpcServiceManager,
            playerModel = playerModel,
            serverClock = serverClock,
            globalJson = AppJson
        )
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun scrobblerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            scrobbler.stopScrobbler()
            scrobbler.scope.cancel()
        }
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.start() {
        scrobbler.startScrobbler()
        advance(200)
        clears = 0
    }

    private suspend fun TestScope.playSong(song: UserSong) {
        playingFlow.value = true
        playerSongId = song.id
        start()
        scrobbler.newSong(song)
        advance(200)
        reports.clear()
        clears = 0
    }

    private suspend fun queueRaw(song: UserSong, payloadListenedAt: Long, msPlayed: Long, rowTimestamp: Long) {
        repository.insertRaw(
            userId = userId,
            songId = song.id.toString(),
            payload = payloadJson.encodeToString(ScrobbleRequest(song.id, payloadListenedAt, msPlayed)),
            timestamp = rowTimestamp,
            target = "server"
        )
    }

    @Test
    fun listenShorterThanThreeSecondsIsNotQueued() = scrobblerTest {
        scrobbler.listenEnded(testSong(0), 2_999L, 1_700_000_000_000L)

        assertEquals(emptyList(), repository.rawRows)
    }

    @Test
    fun listenOfThreeSecondsIsQueuedForTheServerWithItsRequestAsPayload() = scrobblerTest {
        val song = testSong(0)

        scrobbler.listenEnded(song, 3_000L, 1_700_000_000_000L)

        val row = repository.rawRows.single()
        assertEquals("server", row.entry.target)
        assertEquals(userId, row.entry.userId)
        assertEquals(1_700_000_000_000L, row.entry.timestamp)
        assertEquals(song.id.toString(), row.songId)
        assertEquals(
            ScrobbleRequest(songId = song.id, listenedAt = 1_700_000_000_000L, msPlayed = 3_000L),
            payloadJson.decodeFromString<ScrobbleRequest>(row.entry.payload)
        )
        assertEquals(emptyList(), repository.songRows)
    }

    @Test
    fun disabledScrobblerQueuesNoEndedListen() = scrobblerTest {
        Config.setIsServerScrobblingEnabled(false)
        start()

        scrobbler.listenEnded(testSong(0), 10_000L, 1_700_000_000_000L)
        advance(1_000)

        assertEquals(emptyList(), repository.rawRows)
        verify { scrobbleService wasNot Called }
    }

    @Test
    fun disabledScrobblerReportsNoPlayback() = scrobblerTest {
        Config.setIsServerScrobblingEnabled(false)
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = song.id
        start()

        scrobbler.newSong(song)
        advance(1_000)
        seekFlow.tryEmit(5_000L)
        advance(1_000)
        playingFlow.value = false
        advance(20_000)
        scrobbler.newSong(null)
        advance(1_000)

        verify { scrobbleService wasNot Called }
    }

    @Test
    fun disabledScrobblerDoesNotMarkATriggeredListen() = scrobblerTest {
        Config.setIsServerScrobblingEnabled(false)

        scrobbler.triggered(testSong(0), 1_700_000_000_000L)

        assertEquals(BaseScrobbler.ScrobbleStatus.IDLE, scrobbler.status.value)
        verify { scrobbleService wasNot Called }
    }

    @Test
    fun queuedListensAreSentInTimestampOrderAndRemoved() = scrobblerTest {
        val first = testSong(0)
        val second = testSong(1)
        scrobbler.listenEnded(second, 6_000L, 2_000L)
        scrobbler.listenEnded(first, 5_000L, 1_000L)

        scrobbler.startScrobbler()
        advance(1_000)

        assertEquals(
            listOf(
                ScrobbleRequest(songId = first.id, listenedAt = 1_000L, msPlayed = 5_000L),
                ScrobbleRequest(songId = second.id, listenedAt = 2_000L, msPlayed = 6_000L)
            ),
            sent
        )
        assertEquals(emptyList(), repository.rawRows)
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)
    }

    @Test
    fun sentRequestCarriesTheTimestampOfTheQueueRow() = scrobblerTest {
        val song = testSong(0)
        queueRaw(song, payloadListenedAt = 1_000L, msPlayed = 5_000L, rowTimestamp = 2_000L)

        scrobbler.startScrobbler()
        advance(1_000)

        assertEquals(listOf(ScrobbleRequest(songId = song.id, listenedAt = 2_000L, msPlayed = 5_000L)), sent)
    }

    @Test
    fun failedSubmissionKeepsEveryEntryAndStopsTheDrain() = scrobblerTest {
        val first = testSong(0)
        val second = testSong(1)
        scrobbler.listenEnded(first, 5_000L, 1_000L)
        scrobbler.listenEnded(second, 6_000L, 2_000L)
        coEvery { scrobbleService.listened(capture(sent)) } throws IllegalStateException()

        scrobbler.startScrobbler()
        advance(1_000)

        assertEquals(listOf(first.id), sent.map { it.songId })
        assertEquals(2, repository.rawRows.size)
        assertEquals(BaseScrobbler.ScrobbleStatus.FAILED, scrobbler.status.value)
    }

    @Test
    fun serverBecomingReachableResetsTheClockAndDrainsTheQueue() = scrobblerTest {
        reachableFlow.value = false
        start()
        serverClock.record(sentAt = 1_000, serverTime = 5_050, receivedAt = 1_100)
        assertEquals(4_000L, serverClock.offsetMs.value)
        val song = testSong(0)
        queueRaw(song, payloadListenedAt = 1_000L, msPlayed = 5_000L, rowTimestamp = 1_000L)
        advance(1_000)
        assertEquals(emptyList(), sent)

        reachableFlow.value = true
        advance(1_000)

        assertNull(serverClock.offsetMs.value)
        assertEquals(listOf(ScrobbleRequest(songId = song.id, listenedAt = 1_000L, msPlayed = 5_000L)), sent)
        assertEquals(emptyList(), repository.rawRows)
    }

    @Test
    fun undecodableEntryIsDroppedWithoutASendAndTheNextIsSent() = scrobblerTest {
        val song = testSong(0)
        repository.insertRaw(userId, randomPlatformUUID().toString(), "not-json", 1_000L, "server")
        queueRaw(song, payloadListenedAt = 2_000L, msPlayed = 5_000L, rowTimestamp = 2_000L)

        scrobbler.startScrobbler()
        advance(1_000)

        assertEquals(listOf(ScrobbleRequest(songId = song.id, listenedAt = 2_000L, msPlayed = 5_000L)), sent)
        assertEquals(emptyList(), repository.rawRows)
    }

    @Test
    fun newSongWhilePlayingReportsPlaybackOnceAfterTheCoalesceWindow() = scrobblerTest {
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = song.id
        positionFlow.value = 12_345L
        start()

        scrobbler.newSong(song)
        advance(149)

        assertEquals(emptyList(), reports)

        advance(1)

        assertEquals(1, reports.size)

        advance(1_000)

        val report = reports.single()
        assertEquals(song.id, report.songId)
        assertEquals(12_345L, report.positionMs)
        assertTrue(report.isPlaying)
        assertEquals(0, clears)
    }

    @Test
    fun clearedSongClearsNowPlayingWithoutAReport() = scrobblerTest {
        playingFlow.value = true
        start()

        scrobbler.newSong(null)
        advance(20_000)

        assertEquals(1, clears)
        assertEquals(emptyList(), reports)
    }

    @Test
    fun newSongWhileNotPlayingClearsNowPlayingWithoutAReport() = scrobblerTest {
        val song = testSong(0)
        playerSongId = song.id
        start()

        scrobbler.newSong(song)
        advance(20_000)

        assertEquals(1, clears)
        assertEquals(emptyList(), reports)
    }

    @Test
    fun playingSongIsReportedAgainEveryTenSeconds() = scrobblerTest {
        val song = testSong(0)
        playSong(song)

        advance(10_000)

        assertEquals(1, reports.size)

        advance(10_000)

        assertEquals(2, reports.size)
        assertEquals(listOf(song.id, song.id), reports.map { it.songId })
        assertEquals(listOf(true, true), reports.map { it.isPlaying })
    }

    @Test
    fun pauseIsReportedAndNowPlayingClearedAfterOneSecond() = scrobblerTest {
        playSong(testSong(0))

        playingFlow.value = false
        advance(999)

        assertEquals(listOf(false), reports.map { it.isPlaying })
        assertEquals(0, clears)

        advance(1)

        assertEquals(1, clears)
    }

    @Test
    fun pauseStopsTheHeartbeatSoNothingIsReportedAfterThePauseReport() = scrobblerTest {
        playSong(testSong(0))

        playingFlow.value = false
        advance(25_000)

        assertEquals(listOf(false), reports.map { it.isPlaying })
        assertEquals(1, clears)
    }

    @Test
    fun resumeRestartsTheHeartbeat() = scrobblerTest {
        playSong(testSong(0))
        playingFlow.value = false
        advance(2_000)
        reports.clear()

        playingFlow.value = true
        advance(10_149)

        assertEquals(listOf(true), reports.map { it.isPlaying })

        advance(1)

        assertEquals(listOf(true, true), reports.map { it.isPlaying })
    }

    @Test
    fun resumeWithinOneSecondKeepsNowPlaying() = scrobblerTest {
        playSong(testSong(0))

        playingFlow.value = false
        advance(500)
        playingFlow.value = true
        advance(2_000)

        assertEquals(0, clears)
        assertEquals(listOf(false, true), reports.map { it.isPlaying })
    }

    @Test
    fun pauseAndResumeWithinTheCoalesceWindowSendOneReportWithTheLatestState() = scrobblerTest {
        playSong(testSong(0))

        playingFlow.value = false
        advance(100)
        playingFlow.value = true
        advance(1_000)

        assertEquals(listOf(true), reports.map { it.isPlaying })
        assertEquals(0, clears)
    }

    @Test
    fun seekOnTheCurrentSongSendsOneReport() = scrobblerTest {
        val song = testSong(0)
        playSong(song)

        positionFlow.value = 42_000L
        assertTrue(seekFlow.tryEmit(42_000L))
        advance(1_000)

        val report = reports.single()
        assertEquals(song.id, report.songId)
        assertEquals(42_000L, report.positionMs)
        assertTrue(report.isPlaying)
    }

    @Test
    fun songThatIsNoLongerThePlayersCurrentSongIsNotReported() = scrobblerTest {
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = randomPlatformUUID()
        start()

        scrobbler.newSong(song)
        advance(25_000)

        assertEquals(emptyList(), reports)
    }

    @Test
    fun answeredReportSetsTheServerClockOffset() = scrobblerTest {
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = song.id
        coEvery { scrobbleService.reportPlayback(capture(reports)) } answers { currentTimeMillis() + 60_000 }
        start()
        assertNull(serverClock.offsetMs.value)

        scrobbler.newSong(song)
        advance(200)

        assertEquals(1, reports.size)
        val offset = assertNotNull(serverClock.offsetMs.value)
        assertTrue(offset in 55_000L..65_000L)
    }

    @Test
    fun heartbeatAfterTheClockOffsetIsSetCarriesTheServerTime() = scrobblerTest {
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = song.id
        coEvery { scrobbleService.reportPlayback(capture(reports)) } answers { currentTimeMillis() + 60_000 }
        start()
        scrobbler.newSong(song)
        advance(200)
        assertNotNull(serverClock.offsetMs.value)

        val before = currentTimeMillis()
        advance(10_000)

        assertEquals(2, reports.size)
        assertTrue(assertNotNull(reports[1].sentAt) >= before + 55_000L)
    }

    @Test
    fun endedListenWhileTheServerFailsStaysQueuedAndIsSentOnceReachable() = scrobblerTest {
        val song = testSong(0)
        reachableFlow.value = false
        coEvery { scrobbleService.listened(capture(sent)) } throws IllegalStateException()
        start()

        scrobbler.listenEnded(song, 5_000L, 1_000L)
        advance(1_000)

        assertEquals(1, repository.rawRows.size)
        assertEquals(1, sent.size)
        assertEquals(BaseScrobbler.ScrobbleStatus.FAILED, scrobbler.status.value)

        coEvery { scrobbleService.listened(capture(sent)) } just Runs
        reachableFlow.value = true
        advance(1_000)

        assertEquals(2, sent.size)
        assertEquals(ScrobbleRequest(songId = song.id, listenedAt = 1_000L, msPlayed = 5_000L), sent.last())
        assertEquals(emptyList(), repository.rawRows)
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)
    }

    @Test
    fun failingReportKeepsTheHeartbeatRunning() = scrobblerTest {
        val song = testSong(0)
        playingFlow.value = true
        playerSongId = song.id
        coEvery { scrobbleService.reportPlayback(capture(reports)) } throws IllegalStateException()
        start()

        scrobbler.newSong(song)
        advance(200)

        assertEquals(1, reports.size)

        advance(10_000)

        assertEquals(2, reports.size)

        advance(10_000)

        assertEquals(3, reports.size)
        assertNull(serverClock.offsetMs.value)
    }

    @Test
    fun triggeredListenIsMarkedScrobbledWithoutAServiceCall() = scrobblerTest {
        scrobbler.triggered(testSong(0), 1_700_000_000_000L)

        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)
        assertEquals(emptyList(), repository.rawRows)
        verify { scrobbleService wasNot Called }
    }
}
