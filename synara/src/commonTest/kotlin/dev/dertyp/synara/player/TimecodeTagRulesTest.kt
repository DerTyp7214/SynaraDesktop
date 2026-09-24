package dev.dertyp.synara.player

import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimecodeTagRulesTest {
    private val songId = randomPlatformUUID()
    private val userId = randomPlatformUUID()
    private val duration = 200_000L

    private fun chapter(start: Long, end: Long, action: TimecodeTagAction = TimecodeTagAction.NONE, fade: Boolean = false) =
        TimecodeTag(randomPlatformUUID(), userId, songId, TimecodeTagType.CHAPTER, "", start, end, 0L, 0L, action, fade)

    private fun marker(at: Long, action: TimecodeTagAction = TimecodeTagAction.NONE, fade: Boolean = false) =
        TimecodeTag(randomPlatformUUID(), userId, songId, TimecodeTagType.MARKER, "", at, null, 0L, 0L, action, fade)

    private fun tick(tags: List<TimecodeTag>, prev: Long, pos: Long) =
        TimecodeTagRules.decide(tags, prev, pos, duration, songStart = false)

    private fun start(tags: List<TimecodeTag>, pos: Long = 0L) =
        TimecodeTagRules.decide(tags, pos, pos, duration, songStart = true)

    @Test
    fun passiveTagsNeverAct() {
        val tags = listOf(chapter(10_000, 20_000), marker(30_000))
        assertNull(start(tags))
        assertNull(tick(tags, 9_999, 10_000))
        assertNull(tick(tags, 29_999, 30_000))
    }

    @Test
    fun invalidCombinationsAreIgnored() {
        val skipMarker = marker(10_000, TimecodeTagAction.SKIP)
        val skipToChapter = chapter(10_000, 20_000, TimecodeTagAction.SKIP_TO)
        assertFalse(TimecodeTagRules.isActionValid(skipMarker))
        assertFalse(TimecodeTagRules.isActionValid(skipToChapter))
        assertNull(tick(listOf(skipMarker, skipToChapter), 9_999, 10_001))
        assertNull(start(listOf(skipToChapter)))
    }

    @Test
    fun skipChapterSeeksToItsEndWhenCrossed() {
        val tags = listOf(chapter(10_000, 20_000, TimecodeTagAction.SKIP))
        assertNull(tick(tags, 9_000, 9_999))
        assertEquals(TagCommand.Seek(20_000), tick(tags, 9_999, 10_000))
        assertEquals(TagCommand.Seek(20_000), tick(tags, 9_990, 10_050))
    }

    @Test
    fun skipChapterDoesNothingInsideOrBackwards() {
        val tags = listOf(chapter(10_000, 20_000, TimecodeTagAction.SKIP))
        assertNull(tick(tags, 12_000, 12_001))
        assertNull(tick(tags, 10_001, 10_000))
        assertNull(tick(tags, -1, 10_000))
    }

    @Test
    fun skipChapterReachingTheEndFinishesTheTrack() {
        val tags = listOf(chapter(190_000, 200_000, TimecodeTagAction.SKIP))
        assertEquals(TagCommand.FinishTrack, tick(tags, 189_999, 190_000))
    }

    @Test
    fun skipChapterWithFadeFadesOutBeforeAndInAfter() {
        val tags = listOf(chapter(10_000, 20_000, TimecodeTagAction.SKIP, fade = true))
        assertEquals(TagCommand.FadeOut(10_000, 1_500), tick(tags, 8_499, 8_500))
        assertNull(tick(tags, 9_000, 9_001))
        assertEquals(TagCommand.Seek(20_000, 1_500), tick(tags, 9_999, 10_000))
    }

    @Test
    fun fadeIsClampedToHalfTheChapter() {
        val tags = listOf(chapter(10_000, 12_000, TimecodeTagAction.SKIP, fade = true))
        assertEquals(TagCommand.FadeOut(10_000, 1_000), tick(tags, 8_999, 9_000))
        assertEquals(TagCommand.Seek(12_000, 1_000), tick(tags, 9_999, 10_000))
    }

    @Test
    fun skipToMarkerSeeksOnSongStartOnly() {
        val tags = listOf(marker(15_000, TimecodeTagAction.SKIP_TO), marker(40_000, TimecodeTagAction.SKIP_TO))
        assertEquals(TagCommand.Seek(15_000), start(tags))
        assertNull(tick(tags, 14_999, 15_000))
    }

    @Test
    fun skipToMarkerWithFadeFadesIn() {
        val tags = listOf(marker(15_000, TimecodeTagAction.SKIP_TO, fade = true))
        assertEquals(TagCommand.Seek(15_000, TimecodeTagRules.FADE_MS), start(tags))
    }

    @Test
    fun playUntilMarkerFinishesTheTrack() {
        val tags = listOf(marker(60_000, TimecodeTagAction.PLAY_UNTIL))
        assertNull(start(tags))
        assertNull(tick(tags, 59_000, 59_999))
        assertEquals(TagCommand.FinishTrack, tick(tags, 59_999, 60_000))
    }

    @Test
    fun playUntilMarkerWithFadeFadesOutTowardsIt() {
        val tags = listOf(marker(60_000, TimecodeTagAction.PLAY_UNTIL, fade = true))
        assertEquals(TagCommand.FadeOut(60_000, 1_500), tick(tags, 58_499, 58_500))
        assertEquals(TagCommand.FinishTrack, tick(tags, 59_999, 60_000))
    }

    @Test
    fun playUntilMarkerWithFadeActsOnRegularTicks() {
        val tags = listOf(marker(219_377, TimecodeTagAction.PLAY_UNTIL, fade = true))
        val songDuration = 260_000L
        val commands = (210_000L..225_000L step 23L).zipWithNext().mapNotNull { (prev, pos) ->
            TimecodeTagRules.decide(tags, prev, pos, songDuration, songStart = false)?.let { pos to it }
        }
        assertEquals(2, commands.size)
        val (fadeAt, fade) = commands[0]
        assertEquals(TagCommand.FadeOut(219_377, 1_500), fade)
        assertTrue(fadeAt >= 217_877 && fadeAt - 23 < 217_877)
        val (finishAt, finish) = commands[1]
        assertEquals(TagCommand.FinishTrack, finish)
        assertTrue(finishAt >= 219_377 && finishAt - 23 < 219_377)
    }

    @Test
    fun playOnlyStartsAtTheFirstChapterAndChainsTheRest() {
        val tags = listOf(
            chapter(50_000, 60_000, TimecodeTagAction.PLAY_ONLY),
            chapter(20_000, 30_000, TimecodeTagAction.PLAY_ONLY)
        )
        assertEquals(TagCommand.Seek(20_000), start(tags))
        assertNull(tick(tags, 25_000, 25_001))
        assertEquals(TagCommand.Seek(50_000), tick(tags, 29_999, 30_000))
        assertEquals(TagCommand.FinishTrack, tick(tags, 59_999, 60_000))
    }

    @Test
    fun playOnlyStartingAtZeroNeedsNoSeek() {
        val tags = listOf(chapter(0, 30_000, TimecodeTagAction.PLAY_ONLY))
        assertNull(start(tags))
        assertEquals(TagCommand.FinishTrack, tick(tags, 29_999, 30_000))
    }

    @Test
    fun overlappingPlayOnlyChaptersAreMerged() {
        val tags = listOf(
            chapter(10_000, 30_000, TimecodeTagAction.PLAY_ONLY),
            chapter(25_000, 40_000, TimecodeTagAction.PLAY_ONLY, fade = true)
        )
        assertNull(tick(tags, 29_999, 30_000))
        assertEquals(TagCommand.FadeOut(40_000, 1_500), tick(tags, 38_499, 38_500))
        assertEquals(TagCommand.FinishTrack, tick(tags, 39_999, 40_000))
    }

    @Test
    fun playOnlyTakesPrecedenceOverSkipToOnSongStart() {
        val tags = listOf(
            chapter(20_000, 30_000, TimecodeTagAction.PLAY_ONLY),
            marker(5_000, TimecodeTagAction.SKIP_TO)
        )
        assertEquals(TagCommand.Seek(20_000), start(tags))
    }

    @Test
    fun songStartInsideASkipChapterJumpsPastIt() {
        val tags = listOf(chapter(0, 12_000, TimecodeTagAction.SKIP))
        assertEquals(TagCommand.Seek(12_000), start(tags))
    }

    @Test
    fun earliestThresholdWinsWithinOneTick() {
        val tags = listOf(
            marker(10_010, TimecodeTagAction.PLAY_UNTIL),
            chapter(10_000, 20_000, TimecodeTagAction.SKIP)
        )
        assertEquals(TagCommand.Seek(20_000), tick(tags, 9_990, 10_020))
    }

    @Test
    fun allowedActionsFollowTheType() {
        assertEquals(listOf(TimecodeTagAction.NONE), TimecodeTagRules.allowedActions(TimecodeTagType.NOTE))
        assertTrue(TimecodeTagAction.SKIP in TimecodeTagRules.allowedActions(TimecodeTagType.CHAPTER))
        assertTrue(TimecodeTagAction.SKIP_TO in TimecodeTagRules.allowedActions(TimecodeTagType.MARKER))
        assertFalse(TimecodeTagAction.SKIP in TimecodeTagRules.allowedActions(TimecodeTagType.MARKER))
    }
}
