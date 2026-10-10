package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.await
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.StringResource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.radio
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ScrobblerServiceTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    class RecordingScrobbler : BaseScrobbler() {
        override val icon: SynaraIcons = SynaraIcons.Play
        override val name: StringResource = Res.string.radio

        val calls = MutableStateFlow<List<UserSong?>>(emptyList())
        val resets = MutableStateFlow(0)
        val endedListens = MutableStateFlow<List<UserSong>>(emptyList())

        override suspend fun newSong(song: UserSong?) {
            calls.update { it + song }
        }

        override suspend fun reset() {
            resets.update { it + 1 }
        }

        override suspend fun listenEnded(song: UserSong, msPlayed: Long, listenedAt: Long) {
            endedListens.update { it + song }
        }
    }

    class PlayStateAwareScrobbler : BaseScrobbler() {
        override val icon: SynaraIcons = SynaraIcons.Pause
        override val name: StringResource = Res.string.radio

        val calls = MutableStateFlow<List<UserSong?>>(emptyList())
        val resumes = MutableStateFlow<List<UserSong>>(emptyList())

        override suspend fun newSong(song: UserSong?) {
            calls.update { it + song }
        }

        override suspend fun resumed(song: UserSong) {
            resumes.update { it + song }
        }
    }

    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val loadedFlow = MutableStateFlow<PlatformUUID?>(null)
    private val trackStartFlow = MutableSharedFlow<PlayerModel.TrackStart>(extraBufferCapacity = 8)
    private val playerModel = mockk<PlayerModel> {
        every { currentSong } returns songFlow
        every { isPlaying } returns playingFlow
        every { loadedSongId } returns loadedFlow
        every { trackStarts } returns trackStartFlow
    }

    private lateinit var service: ScrobblerService
    private lateinit var scrobbler: RecordingScrobbler
    private lateinit var playStateAware: PlayStateAwareScrobbler

    @BeforeTest
    fun setUp() {
        val koin = startKoin {
            modules(
                module {
                    single { playerModel }
                    single<Logger> { mockk(relaxed = true) }
                    single<SynaraDispatchers> { UnconfinedDispatchers }
                    singleOf(::ScrobblerService)
                    singleOf(::RecordingScrobbler)
                    singleOf(::PlayStateAwareScrobbler)
                }
            )
        }.koin
        service = koin.get()
        scrobbler = koin.get()
        playStateAware = koin.get()
        service.registerScrobbler(RecordingScrobbler::class)
        service.registerScrobbler(PlayStateAwareScrobbler::class)
        service.registerScrobbler(RecordingScrobbler::class)
        assertEquals(listOf(scrobbler, playStateAware), service.registeredScrobblers())
        service.start()
        awaitCalls(1)
    }

    @AfterTest
    fun tearDown() {
        playingFlow.value = false
        service.stop()
        stopKoin()
    }

    private fun song(index: Int) = UserSong(
        id = randomPlatformUUID(),
        title = "Song $index",
        artists = emptyList(),
        album = null,
        duration = 240_000,
        explicit = false,
        path = "/song$index.flac"
    )

    private fun awaitCalls(count: Int): List<UserSong?> = runBlocking {
        withTimeout(5.seconds) { scrobbler.calls.first { it.size == count } }
    }

    private fun startAudio(song: UserSong) {
        loadedFlow.value = song.id
        playingFlow.value = true
    }

    @Test
    fun songSetWhilePausedIsNotAnnounced() {
        songFlow.value = song(0)

        assertEquals(listOf<UserSong?>(null), scrobbler.calls.value)
        assertEquals(null, service.newSong.value)
    }

    @Test
    fun songIsAnnouncedOnceAudioStarts() {
        val song = song(0)
        songFlow.value = song

        startAudio(song)

        assertEquals(listOf(null, song), awaitCalls(2))
    }

    @Test
    fun songChangeWhilePlayingIsAnnouncedOnce() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        songFlow.value = second
        assertEquals(listOf(null, first), scrobbler.calls.value)

        loadedFlow.value = second.id

        assertEquals(listOf(null, first, second), awaitCalls(3))
        assertEquals(emptyList(), playStateAware.resumes.value)
    }

    @Test
    fun unplayableSongIsNeverAnnounced() {
        val first = song(0)
        val second = song(1)
        val third = song(2)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        songFlow.value = second
        playingFlow.value = false
        songFlow.value = third
        assertEquals(listOf(null, first), scrobbler.calls.value)

        startAudio(third)

        assertEquals(listOf(null, first, third), awaitCalls(3))
        assertEquals(third, service.newSong.value)
    }

    @Test
    fun songChangeWhilePausedIsAnnouncedWhenAudioStarts() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        playingFlow.value = false
        songFlow.value = second
        assertEquals(listOf(null, first), scrobbler.calls.value)
        assertEquals(first, service.newSong.value)

        startAudio(second)

        assertEquals(listOf(null, first, second), awaitCalls(3))
    }

    @Test
    fun clearedSongIsAnnouncedAsNull() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)

        songFlow.value = null

        assertEquals(listOf(null, song, null), awaitCalls(3))
    }

    @Test
    fun pauseAndResumeOnTheSameSongIsAnnouncedAgain() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)
        val resetsBefore = scrobbler.resets.value

        playingFlow.value = false
        assertEquals(listOf(null, song), scrobbler.calls.value)
        playingFlow.value = true

        assertEquals(listOf(null, song, song), awaitCalls(3))
        assertEquals(song, service.newSong.value)
        assertEquals(resetsBefore, scrobbler.resets.value)
        assertEquals(emptyList(), scrobbler.endedListens.value)
        assertEquals(null, service.triggeredSong.value)
    }

    @Test
    fun scrobblerHandlingResumeItselfGetsNoSecondAnnouncement() {
        val song = song(0)
        songFlow.value = song
        startAudio(song)
        awaitCalls(2)

        playingFlow.value = false
        playingFlow.value = true
        awaitCalls(3)

        val resumes = runBlocking { withTimeout(5.seconds) { playStateAware.resumes.first { it.size == 1 } } }
        assertEquals(listOf(song), resumes)
        assertEquals(listOf(null, song), playStateAware.calls.value)
    }

    @Test
    fun unregisteredScrobblerLeavesTheList() {
        val before = service.registeredScrobblers()

        service.unregisterScrobbler(PlayStateAwareScrobbler::class)

        assertEquals(listOf<BaseScrobbler>(scrobbler), service.registeredScrobblers())
        assertEquals(listOf(scrobbler, playStateAware), before)
    }

    @Test
    fun audioOfTheNextEntryStartingBeforeItsSongIsKnownIsNotAResume() {
        val first = song(0)
        val second = song(1)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)

        playingFlow.value = false
        startAudio(second)

        assertEquals(listOf(null, first), scrobbler.calls.value)
        assertEquals(emptyList(), playStateAware.resumes.value)

        songFlow.value = second

        assertEquals(listOf(null, first, second), awaitCalls(3))
        assertEquals(emptyList(), playStateAware.resumes.value)
        assertEquals(second, service.newSong.value)
    }

    @Test
    fun listenEndsOncePerPlayedSongAndLoadTimeDoesNotCount() {
        val first = song(0)
        val second = song(1)
        val third = song(2)
        songFlow.value = first
        startAudio(first)
        awaitCalls(2)
        runBlocking { withTimeout(5.seconds) { service.scrobbledFor.first { it >= 1 } } }

        loadedFlow.value = null
        songFlow.value = second

        val ended = runBlocking { withTimeout(5.seconds) { scrobbler.endedListens.first { it.isNotEmpty() } } }
        assertEquals(listOf(first), ended)
        runBlocking { delay(1_500) }
        assertEquals(0, service.scrobbledFor.value)

        songFlow.value = third

        assertEquals(listOf(first), scrobbler.endedListens.value)
        assertEquals(listOf(null, first), scrobbler.calls.value)
    }
}

class ScrobblerRestartTest {

    private class ExecutorDispatchers : SynaraDispatchers {
        val created = CopyOnWriteArrayList<ExecutorCoroutineDispatcher>()

        override fun createNamed(name: String, parallel: Int): CoroutineDispatcher =
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().also { created += it }
    }

    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val loadedFlow = MutableStateFlow<PlatformUUID?>(null)
    private val trackStartFlow = MutableSharedFlow<PlayerModel.TrackStart>(extraBufferCapacity = 8)
    private val playerModel = mockk<PlayerModel> {
        every { currentSong } returns songFlow
        every { isPlaying } returns playingFlow
        every { loadedSongId } returns loadedFlow
        every { trackStarts } returns trackStartFlow
    }
    private val dispatchers = ExecutorDispatchers()

    private lateinit var service: ScrobblerService
    private lateinit var scrobbler: ScrobblerServiceTest.RecordingScrobbler

    @BeforeTest
    fun setUp() {
        val koin = startTestKoin(
            module {
                single { playerModel }
                single<SynaraDispatchers> { dispatchers }
                singleOf(::ScrobblerService)
                single { ScrobblerServiceTest.RecordingScrobbler() }
            }
        )
        service = koin.get()
        scrobbler = koin.get()
    }

    @AfterTest
    fun tearDown() {
        service.stop()
        dispatchers.created.forEach { it.close() }
        stopTestKoin()
    }

    @Test
    fun aScrobblerRegisteredAgainAfterUnregisteringStillReceivesSongs() {
        service.registerScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)
        service.start()
        scrobbler.calls.await { it.size == 1 }
        service.unregisterScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)

        service.registerScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)
        val song = testSong(0)
        songFlow.value = song
        loadedFlow.value = song.id
        playingFlow.value = true

        assertEquals(song, scrobbler.calls.await { it.lastOrNull() == song }.last())
    }

    @Test
    fun aStoppedScrobblerCreatesNoDispatcherUntilItIsStartedAgain() {
        service.registerScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)
        service.start()
        scrobbler.calls.await { it.size == 1 }
        val whileRunning = dispatchers.created.size
        service.unregisterScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)

        val late = scrobbler.scope.launch { scrobbler.resets.update { it + 1 } }

        assertTrue(late.isCancelled)
        assertEquals(whileRunning, dispatchers.created.size)

        service.registerScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)

        assertEquals(whileRunning + 1, dispatchers.created.size)
        assertEquals(0, scrobbler.resets.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobblerDeliveryTest {

    class SubmittingScrobbler : BaseScrobbler() {
        override val icon: SynaraIcons = SynaraIcons.Play
        override val name: StringResource = Res.string.radio

        val announced = mutableListOf<UserSong?>()
        val resumes = mutableListOf<UserSong>()
        val started = mutableListOf<UserSong>()
        val finished = mutableListOf<UserSong>()
        val ended = mutableListOf<UserSong>()
        val endedMs = mutableListOf<Long>()
        val pending = mutableMapOf<PlatformUUID, CompletableDeferred<Unit>>()
        var pendingEnd: CompletableDeferred<Unit>? = null
        var resets = 0

        override suspend fun newSong(song: UserSong?) {
            announced += song
        }

        override suspend fun resumed(song: UserSong) {
            resumes += song
        }

        override suspend fun triggered(song: UserSong, listenedAt: Long) {
            started += song
            pending[song.id]?.await()
            finished += song
        }

        override suspend fun listenEnded(song: UserSong, msPlayed: Long, listenedAt: Long) {
            pendingEnd?.await()
            ended += song
            endedMs += msPlayed
        }

        override suspend fun reset() {
            resets++
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val loadedFlow = MutableStateFlow<PlatformUUID?>(null)
    private val trackStartFlow = MutableSharedFlow<PlayerModel.TrackStart>(extraBufferCapacity = 8)
    private val playerModel = mockk<PlayerModel> {
        every { currentSong } returns songFlow
        every { isPlaying } returns playingFlow
        every { loadedSongId } returns loadedFlow
        every { trackStarts } returns trackStartFlow
    }

    private lateinit var service: ScrobblerService
    private lateinit var scrobbler: SubmittingScrobbler
    private lateinit var recording: ScrobblerServiceTest.RecordingScrobbler

    @BeforeTest
    fun setUp() {
        val koin = startTestKoin(
            module {
                single { playerModel }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                singleOf(::ScrobblerService)
                singleOf(::SubmittingScrobbler)
                single { ScrobblerServiceTest.RecordingScrobbler() }
            }
        )
        service = koin.get()
        scrobbler = koin.get()
        recording = koin.get()
        service.registerScrobbler(SubmittingScrobbler::class)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    private fun deliveryTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        service.start()
        runCurrent()
        try {
            body()
        } finally {
            playingFlow.value = false
            runCurrent()
            service.stop()
        }
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.startAudio(song: UserSong) {
        songFlow.value = song
        loadedFlow.value = song.id
        playingFlow.value = true
        runCurrent()
    }

    @Test
    fun aSongChangeDoesNotCancelASubmissionInFlight() = deliveryTest {
        val first = testSong(0)
        val second = testSong(1)
        val submission = CompletableDeferred<Unit>()
        scrobbler.pending[first.id] = submission
        startAudio(first)
        advance(120_000)
        assertEquals(listOf(first), scrobbler.started)

        startAudio(second)

        assertEquals(listOf(first), scrobbler.ended)
        assertEquals(emptyList(), scrobbler.finished)

        submission.complete(Unit)
        runCurrent()

        assertEquals(listOf(first), scrobbler.finished)
    }

    @Test
    fun aScrobblerStartedAgainDoesNotSubmitTheCurrentListenASecondTime() = deliveryTest {
        val first = testSong(0)
        val second = testSong(1)
        startAudio(first)
        advance(120_000)
        assertEquals(listOf(first), scrobbler.started)

        service.unregisterScrobbler(SubmittingScrobbler::class)
        service.registerScrobbler(SubmittingScrobbler::class)
        runCurrent()
        advance(10_000)

        assertEquals(listOf(first), scrobbler.started)

        startAudio(second)
        advance(120_000)

        assertEquals(listOf(first, second), scrobbler.started)
    }

    @Test
    fun aSubmissionStillInFlightDoesNotHoldBackTheNextSongs() = deliveryTest {
        val first = testSong(0)
        val second = testSong(1)
        scrobbler.pending[first.id] = CompletableDeferred()
        startAudio(first)
        advance(120_000)

        startAudio(second)
        advance(120_000)

        assertEquals(listOf(first, second), scrobbler.started)
        assertEquals(listOf(second), scrobbler.finished)
    }

    @Test
    fun theSameSongStartingAgainEndsTheListenAndIsAnnouncedAndSubmittedAgain() = deliveryTest {
        val song = testSong(0)
        startAudio(song)
        advance(125_000)
        assertEquals(listOf(song), scrobbler.started)

        playingFlow.value = false
        runCurrent()
        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 0L)))
        loadedFlow.value = null
        runCurrent()

        assertEquals(listOf(song), scrobbler.ended)
        assertEquals(0, service.scrobbledFor.value)
        assertNull(service.triggeredSong.value)
        assertEquals(listOf(null, song), scrobbler.announced)

        loadedFlow.value = song.id
        playingFlow.value = true
        runCurrent()

        assertEquals(listOf(null, song, song), scrobbler.announced)
        assertEquals(emptyList(), scrobbler.resumes)

        advance(119_000)

        assertEquals(listOf(song), scrobbler.started)

        advance(1_000)

        assertEquals(listOf(song, song), scrobbler.started)
        assertEquals(listOf(song), scrobbler.ended)
    }

    @Test
    fun aSeamlessRestartIsAnnouncedOnceEvenWhenTheSongIsLoadedAgain() = deliveryTest {
        val song = testSong(0)
        startAudio(song)
        advance(125_000)

        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 0L)))
        runCurrent()

        assertEquals(listOf(song), scrobbler.ended)
        assertEquals(listOf(null, song, song), scrobbler.announced)

        loadedFlow.value = null
        runCurrent()
        loadedFlow.value = song.id
        runCurrent()

        assertEquals(listOf(null, song, song), scrobbler.announced)
        assertEquals(emptyList(), scrobbler.resumes)
        assertEquals(listOf(song), scrobbler.ended)
    }

    @Test
    fun aStartAtAnotherPositionIsNeitherANewListenNorAnAnnouncement() = deliveryTest {
        val song = testSong(0)
        startAudio(song)
        advance(60_000)

        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 30_000L)))
        loadedFlow.value = null
        runCurrent()
        loadedFlow.value = song.id
        runCurrent()

        assertEquals(emptyList(), scrobbler.ended)
        assertEquals(60, service.scrobbledFor.value)
        assertEquals(listOf(null, song), scrobbler.announced)
        assertEquals(emptyList(), scrobbler.resumes)
        assertEquals(1, scrobbler.resets)
    }

    @Test
    fun theTrackStartOfANewSongIsNoSecondListenOrAnnouncement() = deliveryTest {
        val first = testSong(0)
        val second = testSong(1)
        startAudio(first)
        advance(10_000)

        songFlow.value = second
        runCurrent()
        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(second.id, second, 0L)))
        loadedFlow.value = null
        runCurrent()
        loadedFlow.value = second.id
        runCurrent()

        assertEquals(listOf(first), scrobbler.ended)
        assertEquals(listOf(null, first, second), scrobbler.announced)
        assertEquals(emptyList(), scrobbler.resumes)
        assertEquals(2, scrobbler.resets)
    }

    @Test
    fun endingTheCurrentListenOnShutdownDeliversThePlayedTimeAndEndsTheListenOnlyOnce() = deliveryTest {
        val song = testSong(0)
        startAudio(song)
        advance(10_000)

        service.endCurrentListen()

        assertEquals(listOf(song), scrobbler.ended)
        assertEquals(listOf(10_000L), scrobbler.endedMs)
        assertEquals(0, service.scrobbledFor.value)

        startAudio(testSong(1))
        advance(1_000)

        assertEquals(listOf(song), scrobbler.ended)
    }

    @Test
    fun aListenWithoutAFullSecondOfPlayedTimeIsNotEndedOnShutdown() = deliveryTest {
        startAudio(testSong(0))
        advance(999)

        service.endCurrentListen()

        assertEquals(emptyList(), scrobbler.ended)
    }

    @Test
    fun endingTheCurrentListenOnShutdownGivesUpOnAStuckScrobblerAfterTwoSecondsAndStillReachesTheOthers() = deliveryTest {
        service.registerScrobbler(ScrobblerServiceTest.RecordingScrobbler::class)
        scrobbler.pendingEnd = CompletableDeferred()
        val song = testSong(0)
        startAudio(song)
        advance(10_000)
        val before = currentTime

        service.endCurrentListen()

        assertEquals(before + 2_000, currentTime)
        assertEquals(listOf(song), recording.endedListens.value)
        assertEquals(emptyList(), scrobbler.ended)
    }

    @Test
    fun aPauseAfterARestartIsStillAResume() = deliveryTest {
        val song = testSong(0)
        startAudio(song)
        advance(125_000)
        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 0L)))
        runCurrent()

        playingFlow.value = false
        runCurrent()
        playingFlow.value = true
        runCurrent()

        assertEquals(listOf(null, song, song), scrobbler.announced)
        assertEquals(listOf(song), scrobbler.resumes)
        assertEquals(listOf(song), scrobbler.ended)
    }
}
