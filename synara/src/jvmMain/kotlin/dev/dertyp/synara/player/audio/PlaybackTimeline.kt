package dev.dertyp.synara.player.audio

class PlaybackTimeline<S : Any>(segment: S, sampleRate: Int, startFrame: Long) {
    var current: S = segment
        private set
    var pending: S? = null
        private set
    val queuing: S get() = pending ?: current

    var outputFramesPlayed: Long = startFrame
        private set
    var contentFramesPlayed: Long = startFrame
        private set
    var framesQueued: Long = startFrame
        private set

    private var currentSampleRate = sampleRate
    private var currentJoinFrame = 0L
    private var pendingSampleRate = 0
    private var pendingJoinFrame = 0L

    fun onChunkQueued(frames: Int) {
        framesQueued += frames
    }

    fun onBufferUnqueued(outputFrames: Int, contentFrames: Long) {
        outputFramesPlayed += outputFrames
        contentFramesPlayed += contentFrames
    }

    fun playheadFrame(sampleOffset: Int): Long = outputFramesPlayed + sampleOffset

    fun positionMs(contentInCurrentBuffer: Long): Long =
        (contentFramesPlayed + contentInCurrentBuffer - currentJoinFrame) * 1000 / currentSampleRate

    fun attach(segment: S, sampleRate: Int) {
        check(pending == null) { "A segment is already pending" }
        pending = segment
        pendingSampleRate = sampleRate
        pendingJoinFrame = framesQueued
    }

    fun transitionReached(sampleOffset: Int): Boolean =
        pending != null && playheadFrame(sampleOffset) >= pendingJoinFrame

    fun switchToPending() {
        current = checkNotNull(pending) { "No segment is pending" }
        currentSampleRate = pendingSampleRate
        currentJoinFrame = pendingJoinFrame
        pending = null
    }

    fun resetForSoftJoin(segment: S, sampleRate: Int) {
        current = segment
        currentSampleRate = sampleRate
        currentJoinFrame = 0L
        pending = null
        outputFramesPlayed = 0L
        contentFramesPlayed = 0L
        framesQueued = 0L
    }
}
