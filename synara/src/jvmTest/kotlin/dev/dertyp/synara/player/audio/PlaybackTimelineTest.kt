package dev.dertyp.synara.player.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaybackTimelineTest {
    private fun timeline(startFrame: Long = 0L) = PlaybackTimeline("A", RATE, startFrame)

    private fun PlaybackTimeline<String>.queue(vararg frames: Int) = frames.forEach { onChunkQueued(it) }

    private fun PlaybackTimeline<String>.play(vararg frames: Int) = frames.forEach { onBufferUnqueued(it, it.toLong()) }

    @Test
    fun positionOfTheFirstSegmentFollowsThePlayedFramesUpToItsLastFrameWithMixedChunkSizes() {
        val timeline = timeline()
        timeline.queue(1000, 4096, 300)

        assertEquals(0L, timeline.positionMs(0L))
        timeline.play(1000)
        assertEquals(1000L * 1000 / RATE, timeline.positionMs(0L))
        assertEquals((1000L + 2048) * 1000 / RATE, timeline.positionMs(2048L))
        timeline.play(4096)
        assertEquals((5096L + 299) * 1000 / RATE, timeline.positionMs(299L))
        assertEquals(5396L, timeline.framesQueued)
        assertEquals(5096L, timeline.outputFramesPlayed)
        assertEquals(5096L, timeline.contentFramesPlayed)
    }

    @Test
    fun aStartOffsetCountsFromTheStartFrame() {
        val timeline = timeline(startFrame = 30L * RATE)
        timeline.queue(4096)

        assertEquals(30_000L, timeline.positionMs(0L))
        timeline.play(4096)
        assertEquals(30_000L + 4096L * 1000 / RATE, timeline.positionMs(0L))
        assertEquals(30L * RATE + 4096, timeline.framesQueued)
    }

    @Test
    fun attachingKeepsTheCurrentSegmentAndQueuesForThePendingOne() {
        val timeline = timeline()
        timeline.queue(4096, 4096)

        timeline.attach("B", RATE)

        assertEquals("A", timeline.current)
        assertEquals("B", timeline.pending)
        assertEquals("B", timeline.queuing)
        timeline.queue(1000)
        assertEquals(9192L, timeline.framesQueued)
    }

    @Test
    fun noTransitionOneFrameBeforeTheJoinFrame() {
        val timeline = timeline()
        timeline.queue(4096, 4096)
        timeline.attach("B", RATE)
        timeline.queue(4096)
        timeline.play(4096)

        assertFalse(timeline.transitionReached(4095))
        assertEquals("A", timeline.current)
        assertEquals(8191L * 1000 / RATE, timeline.positionMs(4095L))
    }

    @Test
    fun transitionExactlyAtTheJoinFrameAndTheSecondSegmentStartsAtZero() {
        val timeline = timeline()
        timeline.queue(4096, 4096)
        timeline.attach("B", RATE)
        timeline.queue(4096)
        timeline.play(4096)

        assertTrue(timeline.transitionReached(4096))
        timeline.switchToPending()

        assertEquals("B", timeline.current)
        assertNull(timeline.pending)
        assertEquals("B", timeline.queuing)
        assertFalse(timeline.transitionReached(4096))
        timeline.play(4096)
        assertEquals(0L, timeline.positionMs(0L))
        assertEquals(1000L * 1000 / RATE, timeline.positionMs(1000L))
        timeline.play(4096)
        assertEquals(4096L * 1000 / RATE, timeline.positionMs(0L))
    }

    @Test
    fun theJoinFrameIsTheFrameCountQueuedWhenAttachedNotWhenTheSegmentIsSwitched() {
        val timeline = timeline()
        timeline.queue(1000)
        timeline.attach("B", RATE)
        timeline.queue(2000, 3000)
        timeline.play(1000)

        assertTrue(timeline.transitionReached(0))
        timeline.switchToPending()
        assertEquals(0L, timeline.positionMs(0L))
        timeline.play(2000)
        assertEquals(2000L * 1000 / RATE, timeline.positionMs(0L))
    }

    @Test
    fun aSoftJoinResetStartsTheNewSegmentAtZeroWithItsOwnSampleRate() {
        val timeline = timeline()
        timeline.queue(4096, 4096)
        timeline.play(4096, 4096)
        assertEquals(8192L * 1000 / RATE, timeline.positionMs(0L))

        timeline.resetForSoftJoin("B", 96_000)

        assertEquals("B", timeline.current)
        assertNull(timeline.pending)
        assertEquals("B", timeline.queuing)
        assertEquals(0L, timeline.framesQueued)
        assertEquals(0L, timeline.outputFramesPlayed)
        assertEquals(0L, timeline.contentFramesPlayed)
        assertEquals(0L, timeline.positionMs(0L))
        assertEquals(0L, timeline.playheadFrame(0))
        timeline.queue(9600)
        timeline.play(9600)
        assertEquals(100L, timeline.positionMs(0L))
        assertEquals(9600L, timeline.playheadFrame(0))
    }

    @Test
    fun aSoftJoinResetAfterAHardJoinDropsThePendingSegment() {
        val timeline = timeline()
        timeline.queue(4096)
        timeline.attach("B", RATE)

        timeline.resetForSoftJoin("C", RATE)

        assertEquals("C", timeline.current)
        assertNull(timeline.pending)
        assertFalse(timeline.transitionReached(Int.MAX_VALUE))
    }

    @Test
    fun threeSegmentsRunThroughTwoJoinsOnOneTimeline() {
        val timeline = timeline()
        timeline.queue(1000, 1000)
        timeline.attach("B", RATE)
        timeline.queue(500, 500)
        timeline.play(1000)
        assertFalse(timeline.transitionReached(999))
        assertEquals(1999L * 1000 / RATE, timeline.positionMs(999L))
        timeline.play(1000)

        assertTrue(timeline.transitionReached(0))
        timeline.switchToPending()
        assertEquals("B", timeline.current)
        assertEquals(0L, timeline.positionMs(0L))

        timeline.attach("C", RATE)
        assertEquals("C", timeline.queuing)
        timeline.queue(700)
        timeline.play(500)
        assertEquals(500L * 1000 / RATE, timeline.positionMs(0L))
        assertFalse(timeline.transitionReached(499))
        timeline.play(500)
        assertTrue(timeline.transitionReached(0))
        timeline.switchToPending()

        assertEquals("C", timeline.current)
        assertNull(timeline.pending)
        assertEquals(0L, timeline.positionMs(0L))
        assertEquals(350L * 1000 / RATE, timeline.positionMs(350L))
        assertEquals(3000L, timeline.outputFramesPlayed)
        assertEquals(3700L, timeline.framesQueued)
        assertEquals(3350L, timeline.playheadFrame(350))
    }

    @Test
    fun contentFramesAreCountedApartFromOutputFrames() {
        val timeline = timeline()
        timeline.queue(2000)

        timeline.onBufferUnqueued(2000, 3000L)

        assertEquals(2000L, timeline.outputFramesPlayed)
        assertEquals(3000L, timeline.contentFramesPlayed)
        assertEquals(3000L * 1000 / RATE, timeline.positionMs(0L))
        assertEquals(2000L, timeline.playheadFrame(0))
    }

    @Test
    fun theSegmentsAreTheObjectsThatWereAttached() {
        val first = Any()
        val second = Any()
        val timeline = PlaybackTimeline(first, RATE, 0L)
        timeline.attach(second, RATE)

        assertSame(first, timeline.current)
        assertSame(second, timeline.pending)
        timeline.switchToPending()
        assertSame(second, timeline.current)
    }

    private companion object {
        const val RATE = 44_100
    }
}
