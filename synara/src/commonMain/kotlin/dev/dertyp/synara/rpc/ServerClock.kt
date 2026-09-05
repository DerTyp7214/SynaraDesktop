package dev.dertyp.synara.rpc

import dev.dertyp.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerClock {

    private val _offsetMs = MutableStateFlow<Long?>(null)
    val offsetMs: StateFlow<Long?> = _offsetMs.asStateFlow()

    private var bestRtt: Long = Long.MAX_VALUE
    private var lastSampleAt: Long = Long.MIN_VALUE

    fun serverNow(): Long = currentTimeMillis() + (_offsetMs.value ?: 0)

    fun record(sentAt: Long, serverTime: Long, receivedAt: Long) {
        val rtt = receivedAt - sentAt
        if (rtt < 0 || rtt > MAX_RTT_MS) return

        val now = currentTimeMillis()
        val isStale = now - lastSampleAt > STALE_SAMPLE_WINDOW_MS
        if (rtt <= bestRtt || isStale) {
            bestRtt = rtt
            lastSampleAt = now
            _offsetMs.value = serverTime - (sentAt + rtt / 2)
        }
    }

    fun reset() {
        bestRtt = Long.MAX_VALUE
        lastSampleAt = Long.MIN_VALUE
        _offsetMs.value = null
    }

    companion object {
        private const val MAX_RTT_MS = 5_000L
        private const val STALE_SAMPLE_WINDOW_MS = 10 * 60 * 1000L
    }
}
