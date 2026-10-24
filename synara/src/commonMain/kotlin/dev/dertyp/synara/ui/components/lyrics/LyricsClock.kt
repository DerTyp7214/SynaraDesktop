package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.runtime.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class LyricsClock(
    private val maxDriftMs: Long = MAX_DRIFT_MS,
    private val seekGuardMs: Long = SEEK_GUARD_MS
) {
    private var anchorPosition = 0L
    private var anchorNanos = 0L
    private var playing = false
    private var seekGuardUntil = Long.MIN_VALUE

    fun start(position: Long, playing: Boolean, nowNanos: Long) {
        this.playing = playing
        seekGuardUntil = Long.MIN_VALUE
        anchor(position, nowNanos)
    }

    fun onSeek(position: Long, nowNanos: Long) {
        anchor(position, nowNanos)
        seekGuardUntil = nowNanos + seekGuardMs * NANOS_PER_MS
    }

    fun onPosition(position: Long, nowNanos: Long) {
        if (!playing) {
            anchor(position, nowNanos)
            return
        }
        val estimate = positionAt(nowNanos)
        val drift = position - estimate
        if (abs(drift) > maxDriftMs) {
            if (nowNanos < seekGuardUntil) return
            anchor(position, nowNanos)
        } else {
            anchor(estimate + drift / CORRECTION_DIVISOR, nowNanos)
        }
    }

    fun positionAt(nowNanos: Long): Long {
        if (!playing) return anchorPosition
        return anchorPosition + (nowNanos - anchorNanos).coerceAtLeast(0L) / NANOS_PER_MS
    }

    private fun anchor(position: Long, nowNanos: Long) {
        anchorPosition = position
        anchorNanos = nowNanos
    }

    companion object {
        const val MAX_DRIFT_MS = 250L
        const val SEEK_GUARD_MS = 1000L
        const val CORRECTION_DIVISOR = 4L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

@Composable
fun rememberLyricsPosition(
    position: StateFlow<Long>,
    isPlaying: StateFlow<Boolean>,
    seekEvents: Flow<Long>
): LongState {
    val current = remember(position) { mutableLongStateOf(position.value) }
    val playing by isPlaying.collectAsState()
    LaunchedEffect(position, seekEvents, playing) {
        if (!playing) {
            position.collect { current.longValue = it }
            return@LaunchedEffect
        }
        val clock = LyricsClock()
        val reports = Channel<Long>(Channel.CONFLATED)
        val seeks = Channel<Long>(Channel.CONFLATED)
        launch { position.collect { reports.trySend(it) } }
        launch { seekEvents.collect { seeks.trySend(it) } }
        var started = false
        while (isActive) {
            withFrameNanos { now ->
                if (!started) {
                    clock.start(position.value, playing = true, nowNanos = now)
                    reports.tryReceive()
                    started = true
                }
                seeks.tryReceive().getOrNull()?.let { clock.onSeek(it, now) }
                reports.tryReceive().getOrNull()?.let { clock.onPosition(it, now) }
                current.longValue = clock.positionAt(now)
            }
        }
    }
    return current
}

@Composable
fun rememberReportedPosition(position: StateFlow<Long>): LongState {
    val current = remember(position) { mutableLongStateOf(position.value) }
    LaunchedEffect(position) {
        position.collect { current.longValue = it }
    }
    return current
}
