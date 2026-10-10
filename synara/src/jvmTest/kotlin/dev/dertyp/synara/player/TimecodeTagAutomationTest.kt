package dev.dertyp.synara.player

import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val playerModel = mockk<PlayerModel>(relaxed = true) {
        every { trackStarts } returns this@TimecodeTagAutomationTest.trackStarts
        every { seekEvents } returns this@TimecodeTagAutomationTest.seekEvents
        every { currentPosition } returns position
        every { currentSong } returns MutableStateFlow<UserSong?>(null)
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

    private fun startAt(startMs: Long) {
        trackStarts.tryEmit(PlayerModel.TrackStart(songId, null, startMs))
        position.value = startMs
    }

    private fun tickUntil(endMs: Long) {
        while (position.value < endMs) position.value += 1_000
    }

    @Test
    fun trackStartingBeyondTheStartWindowStillRunsCrossingRules() {
        automation(listOf(marker(150_000, TimecodeTagAction.PLAY_UNTIL)))

        startAt(120_000)
        tickUntil(151_000)

        verify(exactly = 1) { playerModel.finishTrack() }
    }

    @Test
    fun trackStartingInsideTheStartWindowRunsTheStartRule() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        startAt(0)
        tickUntil(1_000)

        verify(exactly = 1) { playerModel.seekTo(30_000) }
    }

    @Test
    fun trackStartingBeyondTheStartWindowSkipsTheStartRule() {
        automation(listOf(marker(30_000, TimecodeTagAction.SKIP_TO)))

        startAt(120_000)
        tickUntil(123_000)

        verify(exactly = 0) { playerModel.seekTo(any()) }
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
}
