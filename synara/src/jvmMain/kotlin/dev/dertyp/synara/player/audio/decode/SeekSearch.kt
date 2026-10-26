package dev.dertyp.synara.player.audio.decode

import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.readExactly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

internal object SeekSearch {
    private const val MAX_PROBES = 8
    private const val CHUNK = 64 * 1024
    private const val MAX_SCAN = 1024 * 1024
    private const val DECODE_BUDGET = 256L * 1024

    class Point(val offset: Long, val position: Long)

    class Landing(val offset: Long, val position: Long, val bytesPerPosition: Double, val stream: InputStream)

    sealed interface Scan {
        class Found(val index: Int, val position: Long, val bytesPerPosition: Double) : Scan
        class Resume(val from: Int) : Scan
    }

    fun interface Scanner {
        fun scan(buffer: ByteArray, length: Int, eof: Boolean, from: Int): Scan
    }

    suspend fun land(
        target: Long,
        window: Long,
        start: Point,
        end: Point?,
        hint: Point?,
        probe: suspend (Long) -> Landing?
    ): Landing? {
        var low = start
        var high = end
        var best: Landing? = null
        var last: Landing? = null
        var lastOffset = 0L
        var pending = hint?.takeIf { it.offset > start.offset && it.position <= target }
        if (pending != null) low = pending
        try {
            var probes = 0
            while (probes++ < MAX_PROBES) {
                val bracket = high
                if (pending == null && bracket != null && bracket.offset - low.offset <= DECODE_BUDGET) break
                val offset = pending?.offset ?: nextOffset(target - window / 2, low, high, last, lastOffset) ?: break
                val probed = pending != null
                pending = null
                val landed = probe(offset)
                if (landed == null) {
                    if (probed) low = start
                    high = Point(offset, high?.position ?: break)
                    last = null
                    continue
                }
                last = landed
                lastOffset = offset
                if (landed.position <= target) {
                    best?.stream?.close()
                    best = landed
                    low = Point(landed.offset, landed.position)
                    if (target - landed.position <= window) break
                } else {
                    landed.stream.close()
                    if (probed) low = start
                    high = Point(offset, landed.position)
                }
            }
        } catch (e: Throwable) {
            best?.stream?.close()
            throw e
        }
        return best
    }

    private fun nextOffset(aim: Long, low: Point, high: Point?, last: Landing?, lastOffset: Long): Long? {
        val upper = high ?: return null
        val lowest = low.offset + 1
        val highest = upper.offset - 1
        if (highest < lowest) return null
        val rate = last?.bytesPerPosition
        if (last != null && rate != null && rate.isFinite() && rate > 0) {
            val base = if (last.position <= aim) last.offset else lastOffset
            val newton = base + ((aim - last.position) * rate).toLong()
            if (newton in lowest..highest) return newton
        }
        val span = upper.position - low.position
        val linear = if (span > 0) {
            low.offset + ((aim - low.position).toDouble() / span * (upper.offset - low.offset)).toLong()
        } else (low.offset + upper.offset) / 2
        return linear.coerceIn(lowest, highest)
    }

    suspend fun probe(source: AudioSource, offset: Long, scope: CoroutineScope, scanner: Scanner): Landing? {
        val input = try {
            source.open(offset, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            var buffer = ByteArray(CHUNK)
            var length = 0
            var from = 0
            while (true) {
                length += readExactly(input, buffer, buffer.size - length, length)
                val eof = length < buffer.size
                when (val result = scanner.scan(buffer, length, eof, from)) {
                    is Scan.Found -> return Landing(
                        offset + result.index,
                        result.position,
                        result.bytesPerPosition,
                        SequenceInputStream(ByteArrayInputStream(buffer, result.index, length - result.index), input)
                    )
                    is Scan.Resume -> from = result.from
                }
                if (eof || length >= MAX_SCAN) break
                buffer = buffer.copyOf(buffer.size + CHUNK)
            }
        } catch (e: IOException) {
            input.close()
            return null
        } catch (e: Throwable) {
            input.close()
            throw e
        }
        input.close()
        return null
    }
}
