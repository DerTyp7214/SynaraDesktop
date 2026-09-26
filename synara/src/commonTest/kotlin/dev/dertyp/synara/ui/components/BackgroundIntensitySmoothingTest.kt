package dev.dertyp.synara.ui.components

import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundIntensitySmoothingTest {
    private data class Frame(val time: Long, val intensity: Float, val isPlaying: Boolean)

    private fun restartReplay(initialIntensity: Float, initialPlaying: Boolean, frames: List<Frame>): List<Float> {
        var keyIntensity: Any = initialIntensity
        var keyPlaying = initialPlaying
        var lastTime = 0L
        var smoothed = 0f
        val result = mutableListOf<Float>()
        for (frame in frames) {
            val dt = if (lastTime == 0L) 16L else frame.time - lastTime
            lastTime = frame.time

            val target = if (frame.isPlaying) frame.intensity else 0f
            val lerpFactor = (dt / 16.67f).coerceIn(0f, 1f)

            val riseAlpha = 1f - 0.70f.pow(lerpFactor)
            val fallAlpha = 1f - 0.96f.pow(lerpFactor)

            val alphaValue = if (target > smoothed) riseAlpha else fallAlpha
            smoothed += (target - smoothed) * alphaValue
            result += smoothed

            val composedIntensity: Any = frame.intensity
            if (composedIntensity != keyIntensity || frame.isPlaying != keyPlaying) {
                keyIntensity = composedIntensity
                keyPlaying = frame.isPlaying
                lastTime = 0L
            }
        }
        return result
    }

    private fun singleLoop(initialIntensity: Float, initialPlaying: Boolean, frames: List<Frame>): List<Float> {
        var clock = BackgroundIntensityClock(initialIntensity)
        var keyPlaying = initialPlaying
        var smoothed = 0f
        val result = mutableListOf<Float>()
        for (frame in frames) {
            val dt = clock.frameDelta(frame.time, frame.intensity)
            val target = if (frame.isPlaying) frame.intensity else 0f
            smoothed = smoothBackgroundIntensity(smoothed, target, dt)
            result += smoothed

            if (frame.isPlaying != keyPlaying) {
                keyPlaying = frame.isPlaying
                clock = BackgroundIntensityClock(frame.intensity)
            }
        }
        return result
    }

    private fun assertSameReplay(initialIntensity: Float, initialPlaying: Boolean, frames: List<Frame>) {
        val expected = restartReplay(initialIntensity, initialPlaying, frames)
        val actual = singleLoop(initialIntensity, initialPlaying, frames)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].toRawBits(), actual[i].toRawBits(), "frame $i: expected ${expected[i]}, got ${actual[i]}")
        }
    }

    @Test
    fun scriptedSequenceMatchesRestartSemantics() {
        val script = listOf(
            1000L to 0f, 1016L to 0f, 1033L to 0.4f, 1049L to 0.4f, 1066L to 0.4f,
            1073L to 0.7f, 1080L to 0.2f, 1096L to 0.2f, 1129L to 0.2f, 1229L to 0.9f,
            1237L to 0.9f, 1245L to 0.9f, 1262L to 0.5f, 1263L to 0.5f, 1300L to 0.5f,
            1316L to 0f, 1333L to 0f, 1349L to 1f, 1350L to 1f, 1500L to 1f
        )
        val frames = script.map { (time, intensity) -> Frame(time, intensity, true) }
        assertSameReplay(0f, true, frames)
    }

    @Test
    fun playbackTogglesMatchRestartSemantics() {
        val frames = listOf(
            Frame(2000L, 0.3f, true), Frame(2016L, 0.3f, true), Frame(2033L, 0.3f, false),
            Frame(2049L, 0.3f, false), Frame(2066L, 0.6f, false), Frame(2082L, 0.6f, true),
            Frame(2099L, 0.8f, true), Frame(2115L, 0.8f, false), Frame(2140L, 0.1f, true),
            Frame(2156L, 0.1f, true), Frame(2190L, 0.1f, true)
        )
        assertSameReplay(0.3f, true, frames)
        assertSameReplay(0f, false, frames)
    }

    @Test
    fun randomSequencesMatchRestartSemantics() {
        repeat(20) { seed ->
            val random = Random(seed)
            var time = 5000L + random.nextLong(0, 1000)
            var intensity = random.nextFloat()
            var playing = random.nextBoolean()
            val initialIntensity = intensity
            val initialPlaying = playing
            val frames = List(600) {
                time += listOf(4L, 7L, 8L, 16L, 17L, 33L, 250L)[random.nextInt(7)]
                if (random.nextFloat() < 0.45f) intensity = if (random.nextFloat() < 0.1f) 0f else random.nextFloat()
                if (random.nextFloat() < 0.03f) playing = !playing
                Frame(time, intensity, playing)
            }
            assertSameReplay(initialIntensity, initialPlaying, frames)
        }
    }

    @Test
    fun frameAfterIntensityChangeUsesSixteenMillis() {
        val clock = BackgroundIntensityClock(0.2f)
        assertEquals(16L, clock.frameDelta(100L, 0.2f))
        assertEquals(20L, clock.frameDelta(120L, 0.2f))
        assertEquals(5L, clock.frameDelta(125L, 0.5f))
        assertEquals(16L, clock.frameDelta(175L, 0.5f))
        assertEquals(8L, clock.frameDelta(183L, 0.5f))
    }
}
