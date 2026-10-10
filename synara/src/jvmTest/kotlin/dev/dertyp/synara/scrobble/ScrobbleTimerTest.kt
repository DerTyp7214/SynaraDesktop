package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.testSong
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleTimerTest {

    @Test
    fun startedTimerCountsOncePerSecond() = runTest {
        val timer = ScrobbleTimer(backgroundScope)

        timer.start()
        advance(3_000)

        assertEquals(3, timer.time.value)
    }

    @Test
    fun pauseFreezesTheCount() = runTest {
        val timer = ScrobbleTimer(backgroundScope)
        timer.start()
        advance(3_000)

        timer.pause()
        advance(5_000)

        assertEquals(3, timer.time.value)
    }

    @Test
    fun startingTwiceStillCountsOncePerSecond() = runTest {
        val timer = ScrobbleTimer(backgroundScope)

        timer.start()
        timer.start()
        advance(3_000)

        assertEquals(3, timer.time.value)
    }

    @Test
    fun resetKeepsCountingFromZero() = runTest {
        val timer = ScrobbleTimer(backgroundScope)
        timer.start()
        advance(3_000)

        timer.reset()

        assertEquals(0, timer.time.value)

        advance(2_000)

        assertEquals(2, timer.time.value)
    }

    @Test
    fun stopPausesAndZeroesTheCount() = runTest {
        val timer = ScrobbleTimer(backgroundScope)
        timer.start()
        advance(3_000)

        timer.stop()

        assertEquals(0, timer.time.value)

        advance(5_000)

        assertEquals(0, timer.time.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobblerServiceThresholdTest {
    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private val songFlow = MutableStateFlow<UserSong?>(null)
    private val playingFlow = MutableStateFlow(false)
    private val loadedFlow = MutableStateFlow<PlatformUUID?>(null)
    private val trackStartFlow = MutableSharedFlow<PlayerModel.TrackStart>(extraBufferCapacity = 8)
    private val playerModel = mockk<PlayerModel>()
    private val logger = mockk<Logger>(relaxed = true)

    private fun serviceTest(body: suspend TestScope.(ScrobblerService) -> Unit) = runTest(testDispatcher) {
        every { playerModel.currentSong } returns songFlow
        every { playerModel.isPlaying } returns playingFlow
        every { playerModel.loadedSongId } returns loadedFlow
        every { playerModel.trackStarts } returns trackStartFlow
        val service = ScrobblerService(playerModel, logger, TestDispatchers(testDispatcher))
        service.start()
        runCurrent()
        try {
            body(service)
        } finally {
            playingFlow.value = false
            runCurrent()
            service.stop()
        }
    }

    private fun TestScope.startAudio(song: UserSong) {
        songFlow.value = song
        loadedFlow.value = song.id
        playingFlow.value = true
        runCurrent()
    }

    @Test
    fun songAudibleForHalfItsDurationIsTriggeredOnceWithTheListenStart() = serviceTest { service ->
        val triggered = mutableListOf<TriggeredListen>()
        backgroundScope.launch { service.triggeredSong.filterNotNull().collect { triggered += it } }
        runCurrent()
        val song = testSong(0)

        val before = currentTimeMillis()
        startAudio(song)
        val after = currentTimeMillis()
        advance(119_000)

        assertEquals(emptyList(), triggered)

        advance(1_000)

        assertEquals(1, triggered.size)

        advance(60_000)

        assertEquals(1, triggered.size)
        assertEquals(song, triggered.single().song)
        assertTrue(triggered.single().listenedAt in before..after)
    }

    @Test
    fun pausedTimeDoesNotCountTowardsTheThreshold() = serviceTest { service ->
        val song = testSong(0)
        startAudio(song)
        advance(60_000)

        playingFlow.value = false
        runCurrent()
        advance(300_000)

        assertEquals(60, service.scrobbledFor.value)
        assertNull(service.triggeredSong.value)

        playingFlow.value = true
        runCurrent()
        advance(59_000)

        assertNull(service.triggeredSong.value)

        advance(1_000)

        assertEquals(song, service.triggeredSong.value?.song)
    }

    @Test
    fun songChangeClearsTheTriggeredListen() = serviceTest { service ->
        val first = testSong(0)
        startAudio(first)
        advance(120_000)
        assertEquals(first, service.triggeredSong.value?.song)

        songFlow.value = testSong(1)
        runCurrent()

        assertNull(service.triggeredSong.value)
    }

    @Test
    fun endedListenCarriesThePlayedSecondsAsMilliseconds() = serviceTest { service ->
        val ended = mutableListOf<ListenEnded>()
        backgroundScope.launch { service.listenEnded.collect { ended += it } }
        runCurrent()
        val first = testSong(0)
        startAudio(first)
        advance(125_000)
        val triggered = assertNotNull(service.triggeredSong.value)

        songFlow.value = testSong(1)
        runCurrent()

        assertEquals(listOf(ListenEnded(first, 125_000L, triggered.listenedAt)), ended)
    }

    @Test
    fun aSongWithoutADurationIsTriggeredOnlyAfterTheCapForLongSongs() = serviceTest { service ->
        val song = testSong(0, duration = 0)
        assertEquals(
            BaseScrobbler.requiredDuration(Duration.ZERO, 10.minutes),
            BaseScrobbler.requiredDuration(Duration.ZERO, Duration.ZERO)
        )

        startAudio(song)
        advance(1_000)

        assertNull(service.triggeredSong.value)

        advance(178_000)

        assertNull(service.triggeredSong.value)

        advance(1_000)

        assertEquals(song, service.triggeredSong.value?.song)
    }

    @Test
    fun theSameSongStartingAgainFromItsBeginningEndsTheListenAndIsTriggeredASecondTime() = serviceTest { service ->
        val triggered = mutableListOf<TriggeredListen>()
        val ended = mutableListOf<ListenEnded>()
        backgroundScope.launch { service.triggeredSong.filterNotNull().collect { triggered += it } }
        backgroundScope.launch { service.listenEnded.collect { ended += it } }
        runCurrent()
        val song = testSong(0)
        startAudio(song)
        advance(125_000)
        val firstListen = assertNotNull(service.triggeredSong.value)

        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 0L)))
        runCurrent()

        assertEquals(listOf(ListenEnded(song, 125_000L, firstListen.listenedAt)), ended)
        assertNull(service.triggeredSong.value)
        assertEquals(0, service.scrobbledFor.value)

        advance(119_000)

        assertEquals(1, triggered.size)

        advance(1_000)

        assertEquals(listOf(song, song), triggered.map { it.song })
        assertEquals(1, ended.size)
    }

    @Test
    fun aTrackStartAtAnotherPositionKeepsTheRunningListen() = serviceTest { service ->
        val ended = mutableListOf<ListenEnded>()
        backgroundScope.launch { service.listenEnded.collect { ended += it } }
        runCurrent()
        val song = testSong(0)
        startAudio(song)
        advance(60_000)

        assertTrue(trackStartFlow.tryEmit(PlayerModel.TrackStart(song.id, song, 30_000L)))
        runCurrent()
        advance(60_000)

        assertEquals(emptyList(), ended)
        assertEquals(120, service.scrobbledFor.value)
        assertEquals(song, service.triggeredSong.value?.song)
    }
}
