package dev.dertyp.synara.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class PointerIdleTest {

    private class Harness {
        val activity = mutableIntStateOf(0)
        val active = mutableStateOf(true)
        val timeout = mutableStateOf<Duration>(3.seconds)
        var idle = false
    }

    private fun idleTest(block: ComposeUiTest.(Harness) -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        val harness = Harness()
        setContent {
            harness.idle = rememberIdleAfter(harness.activity.intValue, harness.active.value, harness.timeout.value)
        }
        block(harness)
    }

    private fun ComposeUiTest.advanceTo(millis: Long) {
        val step = millis - mainClock.currentTime - FRAME_MS
        if (step > 0) mainClock.advanceTimeBy(step, ignoreFrameDuration = true)
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.assertIdleFrom(changedAt: Long, timeout: Duration, harness: Harness) {
        advanceTo(changedAt + timeout.inWholeMilliseconds - 1)
        assertFalse(harness.idle, "idle before the timeout elapsed")
        advanceTo(changedAt + timeout.inWholeMilliseconds + 2 * FRAME_MS)
        assertTrue(harness.idle, "not idle once the timeout elapsed")
    }

    private fun ComposeUiTest.change(block: () -> Unit): Long {
        val changedAt = mainClock.currentTime
        block()
        mainClock.advanceTimeByFrame()
        return changedAt
    }

    @Test
    fun becomesIdleAtTimeoutNotBefore() = idleTest { harness ->
        assertFalse(harness.idle)
        assertIdleFrom(mainClock.currentTime, 3.seconds, harness)
    }

    @Test
    fun activityRestartsTheTimeout() = idleTest { harness ->
        advanceTo(mainClock.currentTime + 2_000)
        val changedAt = change { harness.activity.intValue++ }
        assertIdleFrom(changedAt, 3.seconds, harness)

        change { harness.activity.intValue++ }
        assertFalse(harness.idle)
    }

    @Test
    fun activityWhileIdleWakesImmediately() = idleTest { harness ->
        assertIdleFrom(mainClock.currentTime, 3.seconds, harness)
        change { harness.activity.intValue++ }
        assertFalse(harness.idle)
    }

    @Test
    fun timeoutChangeRestartsTheTimer() = idleTest { harness ->
        advanceTo(mainClock.currentTime + 2_000)
        val changedAt = change { harness.timeout.value = 5.seconds }
        assertIdleFrom(changedAt, 5.seconds, harness)
    }

    @Test
    fun inactiveNeverBecomesIdle() = idleTest { harness ->
        change { harness.active.value = false }
        advanceTo(mainClock.currentTime + 60_000)
        assertFalse(harness.idle)
    }

    @Test
    fun reactivationStartsAFreshTimeout() = idleTest { harness ->
        assertIdleFrom(mainClock.currentTime, 3.seconds, harness)

        change { harness.active.value = false }
        assertFalse(harness.idle)

        val changedAt = change { harness.active.value = true }
        assertFalse(harness.idle)
        assertIdleFrom(changedAt, 3.seconds, harness)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
