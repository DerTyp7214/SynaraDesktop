package dev.dertyp.synara.player.audio

class PlayheadEstimator {
    private var started = false
    private var rawFrame = 0L
    private var stepFrames = 0L
    private var anchorFrame = 0L
    private var anchorNanos = 0L
    private var estimate = 0L

    fun reset() {
        started = false
        stepFrames = 0L
    }

    fun estimate(rawFrame: Long, nowNanos: Long, framesPerSecond: Int, advancing: Boolean, limitFrame: Long): Long {
        if (!started || rawFrame < this.rawFrame) {
            started = true
            stepFrames = 0L
            this.rawFrame = rawFrame
            anchorFrame = rawFrame
            anchorNanos = nowNanos
            estimate = rawFrame
            return estimate
        }
        if (rawFrame > this.rawFrame) {
            if (advancing) stepFrames = rawFrame - this.rawFrame
            this.rawFrame = rawFrame
            anchorFrame = rawFrame
            anchorNanos = nowNanos
            if (estimate < rawFrame) estimate = rawFrame
        }
        if (!advancing) {
            anchorFrame = estimate
            anchorNanos = nowNanos
            return estimate
        }
        if (stepFrames == 0L || framesPerSecond <= 0) return estimate

        val elapsedNanos = (nowNanos - anchorNanos).coerceAtLeast(0L)
        val stepNanos = stepFrames * NANOS_PER_SECOND / framesPerSecond
        val advanced = if (elapsedNanos >= stepNanos) stepFrames else elapsedNanos * framesPerSecond / NANOS_PER_SECOND
        val candidate = minOf(anchorFrame + advanced, rawFrame + stepFrames, limitFrame)
        if (candidate > estimate) estimate = candidate
        return estimate
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
