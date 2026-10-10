package dev.dertyp.synara.rpc

class FallbackFlipGate(private val windowMs: Long, private val now: () -> Long) {
    private var lastFlipAt: Long? = null

    fun shouldFlip(): Boolean = synchronized(this) {
        val current = now()
        val last = lastFlipAt
        if (last != null && current - last < windowMs) return@synchronized false
        lastFlipAt = current
        true
    }
}
