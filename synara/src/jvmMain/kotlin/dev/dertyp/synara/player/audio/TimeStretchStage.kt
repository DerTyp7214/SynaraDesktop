package dev.dertyp.synara.player.audio

import org.lwjgl.BufferUtils
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

class PcmChunk(val pcm: ShortBuffer, val outputFrames: Int, val contentFrames: Long)

class TimeStretchStage(
    sampleRate: Int,
    private val channels: Int,
    private val speed: () -> Float,
    private val upstream: suspend () -> ShortBuffer?
) {
    private val sonic = Sonic(sampleRate, channels)
    private val minOutputFrames = (sampleRate * MIN_CHUNK_MS / 1000).coerceAtLeast(1)
    private var ended = false

    suspend fun next(): PcmChunk? {
        while (true) {
            val currentSpeed = speed().coerceIn(MIN_SPEED, MAX_SPEED)
            if (sonic.isEmpty) {
                if (ended) return null
                if (abs(currentSpeed - 1f) < SPEED_EPSILON) {
                    val buffer = upstream()
                    if (buffer == null) {
                        ended = true
                        return null
                    }
                    val frames = buffer.remaining() / channels
                    if (frames <= 0) continue
                    return PcmChunk(buffer, frames, frames.toLong())
                }
            }
            sonic.speed = currentSpeed
            while (sonic.outputFrameCount < minOutputFrames && !ended) {
                val buffer = upstream()
                if (buffer == null) {
                    ended = true
                    sonic.queueEndOfStream()
                } else {
                    sonic.queueInput(buffer)
                }
            }
            val frames = sonic.outputFrameCount
            if (frames <= 0) {
                if (ended) return null
                continue
            }
            val output = BufferUtils.createShortBuffer(frames * channels)
            sonic.getOutput(output)
            output.flip()
            return PcmChunk(output, frames, (frames * currentSpeed.toDouble()).roundToLong())
        }
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        private const val MIN_CHUNK_MS = 150
        private const val SPEED_EPSILON = 0.001f
    }
}
