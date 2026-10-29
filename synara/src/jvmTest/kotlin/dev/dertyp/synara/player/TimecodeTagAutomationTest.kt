package dev.dertyp.synara.player

import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

class TimecodeTagAutomationTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val songId = randomPlatformUUID()
    private val userId = randomPlatformUUID()
    private val trackStarts = MutableSharedFlow<PlayerModel.TrackStart>(extraBufferCapacity = 8)
    private val seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private val position = MutableStateFlow(0L)
    private val currentSong = MutableStateFlow<UserSong?>(null)
    private val playerModel = mockk<PlayerModel>(relaxed = true) {
        every { trackStarts } returns this@TimecodeTagAutomationTest.trackStarts
        every { seekEvents } returns this@TimecodeTagAutomationTest.seekEvents
        every { currentPosition } returns position
        every { currentSong } returns this@TimecodeTagAutomationTest.currentSong
        every { duration } returns MutableStateFlow(200_000L)
        every { isRemote } returns false
    }
    private val tagStore = mockk<TimecodeTagStore>(relaxed = true)

    private fun marker(at: Long, action: TimecodeTagAction) =
        TimecodeTag(randomPlatformUUID(), userId, songId, TimecodeTagType.MARKER, "", at, null, 0L, 0L, action, false)

    private fun automation(tags: List<TimecodeTag>): TimecodeTagAutomation {
        every { tagStore.cached(songId) } returns tags
        return TimecodeTagAutomation(playerModel, tagStore, mockk<Logger>(relaxed = true), UnconfinedDispatchers).also { it.start() }
    }

    private fun startAt(startMs: Long): PlayerModel.TrackStart {
        val start = PlayerModel.TrackStart(songId, null, startMs)
        trackStarts.tryEmit(start)
        position.value = startMs
        return start
    }

    private fun tickUntil(endMs: Long) {
        while (position.value < endMs) position.value += 1_000
    }

    @Test
    fun trackStartingBeyondTheStartWindowStillRunsCrossingRules() {
        automation(listOf(marker(150_000, TimecodeTagAction.PLAY_UNTIL)))

        startAt(120_000)
        tickUntil(151_000)

        verify(exactly = 1) { playerModel.finishTrack(any()) }
    }

    @Test
    fun trackStartingInsideTheStartWindowRunsTheStartRule() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        startAt(0)
        tickUntil(1_000)

        verify(exactly = 1) { playerModel.seekTo(30_000, any()) }
    }

    @Test
    fun aSeekOntoTheTrackStartFadesInLikeTheStartRule() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO).copy(fade = true)))
        startAt(120_000)

        seekEvents.tryEmit(30_000)
        position.value = 30_000
        tickUntil(30_000 + TimecodeTagRules.FADE_MS)

        verifyOrder {
            playerModel.setFadeGain(0f)
            playerModel.setFadeGain(1f)
        }
    }

    @Test
    fun aSeekElsewhereDoesNotFadeIn() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO).copy(fade = true)))
        startAt(120_000)

        seekEvents.tryEmit(50_000)
        position.value = 50_000

        verify(exactly = 0) { playerModel.setFadeGain(any()) }
    }

    @Test
    fun trackStartingBeyondTheStartWindowSkipsTheStartRule() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        startAt(120_000)
        tickUntil(123_000)

        verify(exactly = 0) { playerModel.seekTo(any()) }
        verify(exactly = 0) { playerModel.seekTo(any(), any()) }
    }

    @Test
    fun aSeekNamesTheStartOfTheTrackTheRuleFiredFor() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        val start = startAt(0)
        tickUntil(1_000)

        verify(exactly = 1) { playerModel.seekTo(30_000, start) }
        verify(exactly = 0) { playerModel.seekTo(any()) }
    }

    @Test
    fun aSeekForAnAdoptedSongWithoutAKnownStartIsNotScoped() {
        currentSong.value = testSong(0, id = songId, duration = 200_000)

        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        verify(exactly = 1) { playerModel.seekTo(30_000) }
        verify(exactly = 0) { playerModel.seekTo(any(), any()) }
    }

    @Test
    fun tagsOfATrackAreRequestedOncePerStartNotPerTick() {
        automation(emptyList())
        every { tagStore.cached(songId) } returns null

        startAt(0)
        tickUntil(10_000)

        verify(exactly = 1) { tagStore.ensureLoaded(songId) }

        startAt(0)
        tickUntil(5_000)

        verify(exactly = 2) { tagStore.ensureLoaded(songId) }
    }

    @Test
    fun aFinishNamesTheStartOfTheTrackTheRuleFiredFor() {
        automation(listOf(marker(150_000, TimecodeTagAction.PLAY_UNTIL)))

        val start = startAt(120_000)
        tickUntil(151_000)

        verify(exactly = 1) { playerModel.finishTrack(start) }
    }

    @Test
    fun eachFinishOfTheSameSongNamesItsOwnStart() {
        automation(listOf(marker(150_000, TimecodeTagAction.PLAY_UNTIL)))

        val first = startAt(120_000)
        tickUntil(151_000)
        val second = startAt(121_000)
        tickUntil(151_000)

        verifyOrder {
            playerModel.finishTrack(first)
            playerModel.finishTrack(second)
        }
        verify(exactly = 2) { playerModel.finishTrack(any()) }
    }

    @Test
    fun aFinishForAnAdoptedSongWithoutAKnownStartIsNotRequested() {
        currentSong.value = testSong(0, id = songId, duration = 200_000)
        automation(listOf(marker(150_000, TimecodeTagAction.PLAY_UNTIL)))

        position.value = 120_000
        tickUntil(151_000)

        verify(exactly = 0) { playerModel.finishTrack(any()) }
    }
}
