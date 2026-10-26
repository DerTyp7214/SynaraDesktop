package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

internal interface FrameLayout {
    val headerLength: Int
    val samplesPerFrame: Int
    val sampleRate: Int

    fun frameLength(bytes: ByteArray, offset: Int): Int?

    fun confirm(bytes: ByteArray, offset: Int, length: Int): Boolean
}

internal class FrameIndex(
    val checkpoints: LongArray,
    val frames: Long,
    val endOffset: Long,
    val complete: Boolean,
)

internal class FrameStart(
    val input: InputStream,
    val discardFrames: Int,
    private val trimSamples: Long,
    private val sampleRate: Int,
    val startMs: Long,
) {
    fun trimFrames(format: PcmFormat): Long = trimSamples * format.sampleRate / sampleRate
}

// Formats without timestamps: frame headers are walked from the audio start to find the frame holding the target.
internal object FrameSeek {
    const val WARMUP_FRAMES = 24
    const val CHECKPOINT_FRAMES = 8
    const val SCAN_BUDGET = 32L * 1024 * 1024
    private const val CACHE_SIZE = 8

    private val cache = object : LinkedHashMap<String, FrameIndex>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FrameIndex>?): Boolean = size > CACHE_SIZE
    }

    suspend fun start(
        source: AudioSource,
        layout: FrameLayout,
        audioStart: Long,
        size: Long,
        startMs: Long,
        scope: CoroutineScope,
        scanBudget: Long = SCAN_BUDGET,
        locate: (frame: Long) -> Long,
        landedFrame: (offset: Long, frame: Long) -> Long,
    ): FrameStart? = withContext(AppDispatchers.io) {
        val samplesPerFrame = layout.samplesPerFrame.toLong()
        val target = startMs * layout.sampleRate / 1000
        val frame = target / samplesPerFrame
        if (frame < WARMUP_FRAMES) {
            val input = source.open(audioStart, scope) ?: return@withContext null
            return@withContext FrameStart(input, frame.toInt(), target - frame * samplesPerFrame, layout.sampleRate, startMs)
        }

        val key = "${source.cacheKey}:$size:$audioStart"
        var index = synchronized(cache) { cache[key] } ?: FrameIndex(LongArray(0), 0, audioStart, complete = false)
        if (!index.complete && index.frames <= frame) {
            if (locate(frame) - index.endOffset > scanBudget) {
                return@withContext land(source, layout, target, frame, scope, locate, landedFrame, startMs)
            }
            val input = source.open(index.endOffset, scope) ?: return@withContext null
            index = input.use { scan(index, it, layout, frame) }
            synchronized(cache) { cache[key] = index }
        }
        if (index.checkpoints.isEmpty()) return@withContext null

        val reached = frame.coerceAtMost(index.frames)
        val first = (reached - WARMUP_FRAMES).coerceAtLeast(0) / CHECKPOINT_FRAMES * CHECKPOINT_FRAMES
        val input = source.open(index.checkpoints[(first / CHECKPOINT_FRAMES).toInt()], scope) ?: return@withContext null
        if (reached == frame) {
            FrameStart(input, (frame - first).toInt(), target - frame * samplesPerFrame, layout.sampleRate, startMs)
        } else {
            FrameStart(input, (reached - first).toInt(), 0, layout.sampleRate, reached * samplesPerFrame * 1000 / layout.sampleRate)
        }
    }

    private suspend fun land(
        source: AudioSource,
        layout: FrameLayout,
        target: Long,
        frame: Long,
        scope: CoroutineScope,
        locate: (Long) -> Long,
        landedFrame: (Long, Long) -> Long,
        startMs: Long,
    ): FrameStart? {
        val wanted = frame - WARMUP_FRAMES
        val offset = locate(wanted)
        val raw = source.open(offset, scope) ?: return null
        val synced = FrameSync.sync(raw, layout::confirm) ?: run {
            raw.close()
            return null
        }
        val landed = landedFrame(offset + synced.skipped, wanted)
        val discard = (frame - landed).coerceAtLeast(WARMUP_FRAMES.toLong())
        val first = landed + discard
        val samplesPerFrame = layout.samplesPerFrame.toLong()
        return if (first == frame) {
            FrameStart(synced.stream, discard.toInt(), target - frame * samplesPerFrame, layout.sampleRate, startMs)
        } else {
            FrameStart(synced.stream, discard.toInt(), 0, layout.sampleRate, first * samplesPerFrame * 1000 / layout.sampleRate)
        }
    }

    private suspend fun scan(index: FrameIndex, input: InputStream, layout: FrameLayout, until: Long): FrameIndex {
        var checkpoints = index.checkpoints.copyOf(maxOf(index.checkpoints.size * 2, 1024))
        var count = index.checkpoints.size
        var frames = index.frames
        var reader = FrameReader(input, index.endOffset)
        var complete = false
        while (frames <= until) {
            if (frames % 1024 == 0L) currentCoroutineContext().ensureActive()
            val length = reader.frameLength(layout)
            if (length == null) {
                val synced = reader.resync(layout)
                if (synced == null) {
                    complete = true
                    break
                }
                reader = synced
                continue
            }
            val frameStart = reader.position
            if (!reader.skip(length)) {
                complete = true
                break
            }
            if (frames % CHECKPOINT_FRAMES == 0L) {
                if (count == checkpoints.size) checkpoints = checkpoints.copyOf(count * 2)
                checkpoints[count++] = frameStart
            }
            frames++
        }
        return FrameIndex(checkpoints.copyOf(count), frames, reader.position, complete)
    }

    private class FrameReader(private val input: InputStream, var position: Long) {
        private var buffer = ByteArray(CHUNK)
        private var start = 0
        private var end = 0

        private fun fill(count: Int): Boolean {
            if (end - start >= count) return true
            if (buffer.size - start < count) {
                val target = if (buffer.size < count) ByteArray(count + CHUNK) else buffer
                System.arraycopy(buffer, start, target, 0, end - start)
                end -= start
                start = 0
                buffer = target
            }
            while (end - start < count) {
                val read = input.read(buffer, end, buffer.size - end)
                if (read == -1) return false
                end += read
            }
            return true
        }

        fun frameLength(layout: FrameLayout): Int? {
            if (!fill(layout.headerLength)) return null
            return layout.frameLength(buffer, start)?.takeIf { it > layout.headerLength }
        }

        fun skip(count: Int): Boolean {
            if (!fill(count)) return false
            start += count
            position += count
            return true
        }

        fun resync(layout: FrameLayout): FrameReader? {
            if (!skip(1)) return null
            val rest = SequenceInputStream(ByteArrayInputStream(buffer, start, end - start), input)
            val synced = FrameSync.sync(rest, layout::confirm) ?: return null
            return FrameReader(synced.stream, position + synced.skipped)
        }

        companion object {
            private const val CHUNK = 64 * 1024
        }
    }
}
