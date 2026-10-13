package dev.dertyp.synara.player.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayheadEstimatorTest {
    private fun PlayheadEstimator.at(rawFrame: Long, nowNanos: Long, advancing: Boolean = true, limitFrame: Long = Long.MAX_VALUE) =
        estimate(rawFrame, nowNanos, RATE, advancing, limitFrame)

    private fun estimatorAfterOneStep(): PlayheadEstimator {
        val estimator = PlayheadEstimator()
        assertEquals(0L, estimator.at(0L, 0L))
        assertEquals(STEP, estimator.at(STEP, PERIOD_NANOS))
        return estimator
    }

    @Test
    fun theEstimateAdvancesEveryMillisecondBetweenTwoRawStepsAndReachesEverySpectrumFrame() {
        val estimator = estimatorAfterOneStep()

        val estimates = (1..85L).map { ms -> estimator.at(STEP, PERIOD_NANOS + ms * MILLI_NANOS) }

        estimates.forEachIndexed { index, estimate -> assertEquals(STEP + 48L * (index + 1), estimate) }
        assertEquals(85, estimates.distinct().size)
        for (spectrumFrame in 1 until STEP / SPECTRUM_FRAMES) {
            val start = STEP + spectrumFrame * SPECTRUM_FRAMES
            assertTrue(estimates.any { it >= start && it < start + SPECTRUM_FRAMES }, "Spectrum frame at $start was skipped")
        }
        assertEquals(2 * STEP, estimator.at(2 * STEP, 2 * PERIOD_NANOS))
    }

    @Test
    fun aStalledRawPlayheadStopsTheEstimateOneStepAhead() {
        val estimator = estimatorAfterOneStep()

        assertEquals(STEP + 48L * 85, estimator.at(STEP, PERIOD_NANOS + 85 * MILLI_NANOS))
        assertEquals(2 * STEP, estimator.at(STEP, 2 * PERIOD_NANOS))
        assertEquals(2 * STEP, estimator.at(STEP, 3 * PERIOD_NANOS))
        assertEquals(2 * STEP, estimator.at(STEP, 10_000 * MILLI_NANOS))
        assertEquals(2 * STEP, estimator.at(STEP, Long.MAX_VALUE))
    }

    @Test
    fun theEstimateDoesNotMoveWhileTheSourceIsNotAdvancingAndContinuesFromThereAfterwards() {
        val estimator = estimatorAfterOneStep()
        assertEquals(STEP + 480L, estimator.at(STEP, PERIOD_NANOS + 10 * MILLI_NANOS))

        assertEquals(STEP + 480L, estimator.at(STEP, PERIOD_NANOS + 20 * MILLI_NANOS, advancing = false))
        assertEquals(STEP + 480L, estimator.at(STEP, PERIOD_NANOS + 500 * MILLI_NANOS, advancing = false))

        assertEquals(STEP + 480L + 48L, estimator.at(STEP, PERIOD_NANOS + 501 * MILLI_NANOS))
        assertEquals(STEP + 480L + 96L, estimator.at(STEP, PERIOD_NANOS + 502 * MILLI_NANOS))
    }

    @Test
    fun aRawStepThatLandsBehindTheEstimateHoldsTheEstimateUntilTheClockCatchesUp() {
        val estimator = estimatorAfterOneStep()
        assertEquals(2 * STEP, estimator.at(STEP, 2 * PERIOD_NANOS))
        val lateRaw = 2 * STEP - 192
        val lateNanos = 2 * PERIOD_NANOS + MILLI_NANOS

        assertEquals(2 * STEP, estimator.at(lateRaw, lateNanos))

        var previous = 2 * STEP
        for (ms in 1..10L) {
            val estimate = estimator.at(lateRaw, lateNanos + ms * MILLI_NANOS)
            assertTrue(estimate >= previous, "Estimate went back from $previous to $estimate")
            assertEquals(maxOf(2 * STEP, lateRaw + 48L * ms), estimate)
            previous = estimate
        }
        assertEquals(lateRaw + 480L, previous)
    }

    @Test
    fun theEstimateNeverExceedsTheUpperLimit() {
        val estimator = estimatorAfterOneStep()

        assertEquals(STEP + 480L, estimator.at(STEP, PERIOD_NANOS + 10 * MILLI_NANOS, limitFrame = STEP + 1000))
        assertEquals(STEP + 1000L, estimator.at(STEP, PERIOD_NANOS + 30 * MILLI_NANOS, limitFrame = STEP + 1000))
        assertEquals(STEP + 1000L, estimator.at(STEP, PERIOD_NANOS + 31 * MILLI_NANOS, limitFrame = STEP + 1000))
        assertEquals(STEP + 1900L, estimator.at(STEP, PERIOD_NANOS + 40 * MILLI_NANOS, limitFrame = STEP + 1900))
        assertEquals(STEP + 1900L, estimator.at(STEP, 3 * PERIOD_NANOS, limitFrame = STEP + 1900))
    }

    @Test
    fun aRawPlayheadThatGoesBackwardsResetsTheEstimateAndTheLearnedStep() {
        val estimator = estimatorAfterOneStep()
        assertEquals(STEP + 480L, estimator.at(STEP, PERIOD_NANOS + 10 * MILLI_NANOS))

        assertEquals(1000L, estimator.at(1000L, PERIOD_NANOS + 11 * MILLI_NANOS))

        assertEquals(1000L, estimator.at(1000L, PERIOD_NANOS + 50 * MILLI_NANOS))
        assertEquals(1000L, estimator.at(1000L, 10 * PERIOD_NANOS))
        assertEquals(1000L + STEP, estimator.at(1000L + STEP, 11 * PERIOD_NANOS))
        assertEquals(1000L + STEP + 48L, estimator.at(1000L + STEP, 11 * PERIOD_NANOS + MILLI_NANOS))
    }

    @Test
    fun resetDropsTheStateEvenWhenTheNextRawPlayheadIsFurtherAhead() {
        val estimator = estimatorAfterOneStep()
        assertEquals(2 * STEP, estimator.at(STEP, 2 * PERIOD_NANOS))

        estimator.reset()

        assertEquals(STEP / 2, estimator.at(STEP / 2, 2 * PERIOD_NANOS + MILLI_NANOS))
        estimator.reset()
        assertEquals(9 * STEP, estimator.at(9 * STEP, 2 * PERIOD_NANOS + 2 * MILLI_NANOS))
        assertEquals(9 * STEP, estimator.at(9 * STEP, 5 * PERIOD_NANOS))
    }

    @Test
    fun nothingIsExtrapolatedBeforeAStepWasObserved() {
        val estimator = PlayheadEstimator()

        assertEquals(0L, estimator.at(0L, 0L))
        assertEquals(0L, estimator.at(0L, 50 * MILLI_NANOS))
        assertEquals(0L, estimator.at(0L, 10_000 * MILLI_NANOS))
    }

    @Test
    fun aRawJumpWhileNotAdvancingIsFollowedButNotLearnedAsStep() {
        val estimator = PlayheadEstimator()
        assertEquals(0L, estimator.at(0L, 0L))

        assertEquals(20_000L, estimator.at(20_000L, PERIOD_NANOS, advancing = false))

        assertEquals(20_000L, estimator.at(20_000L, 2 * PERIOD_NANOS))
        assertEquals(20_000L, estimator.at(20_000L, 20 * PERIOD_NANOS))
    }

    private companion object {
        const val RATE = 48_000
        const val STEP = 4096L
        const val SPECTRUM_FRAMES = 256L
        const val MILLI_NANOS = 1_000_000L
        const val PERIOD_NANOS = STEP * 1_000_000_000L / RATE
    }
}
