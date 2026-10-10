package dev.dertyp.synara.player.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FlowInputStreamTest {
    private class UpstreamFailure : RuntimeException()

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun stream(flow: Flow<ByteArray>) = FlowInputStream(flow, scope)

    private fun <T> bounded(block: () -> T): T = CompletableFuture.supplyAsync(block).get(10, TimeUnit.SECONDS)

    private fun <T> awaitDeferred(deferred: CompletableDeferred<T>): T = runBlocking { withTimeout(10.seconds) { deferred.await() } }

    private fun InputStream.drainByteWise(): List<Int> = bounded {
        val out = mutableListOf<Int>()
        while (true) {
            val value = read()
            if (value == -1) break
            out.add(value)
        }
        out
    }

    private fun InputStream.bulk(len: Int): ByteArray? = bounded {
        val buffer = ByteArray(len)
        val count = read(buffer, 0, len)
        if (count == -1) null else buffer.copyOf(count)
    }

    @Test
    fun readsChunksByteWiseInOrderThenEof() {
        val stream = stream(flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        assertEquals(listOf(1, 2, 3, 4, 5), stream.drainByteWise())
        assertEquals(-1, bounded { stream.read() })
    }

    @Test
    fun readsChunksInBulkInOrderThenEof() {
        val stream = stream(flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        assertContentEquals(byteArrayOf(1, 2, 3), stream.bulk(100))
        assertContentEquals(byteArrayOf(4, 5), stream.bulk(100))
        assertEquals(null, stream.bulk(100))
    }

    @Test
    fun partialReadHonoursRequestedLength() {
        val stream = stream(flowOf(byteArrayOf(1, 2, 3, 4, 5)))
        assertContentEquals(byteArrayOf(1, 2), stream.bulk(2))
        assertContentEquals(byteArrayOf(3, 4), stream.bulk(2))
        assertContentEquals(byteArrayOf(5), stream.bulk(2))
    }

    @Test
    fun bulkReadReturnsAtMostOneChunkPerCall() {
        val stream = stream(flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
        assertContentEquals(byteArrayOf(1, 2, 3), stream.bulk(10))
        assertContentEquals(byteArrayOf(4, 5, 6), stream.bulk(10))
    }

    @Test
    fun readWithOffsetWritesAtThatOffset() {
        val stream = stream(flowOf(byteArrayOf(7, 8, 9)))
        val buffer = ByteArray(6)
        val count = bounded { stream.read(buffer, 2, 3) }
        assertEquals(3, count)
        assertContentEquals(byteArrayOf(0, 0, 7, 8, 9, 0), buffer)
    }

    @Test
    fun byteWithAllBitsSetReadsAs255() {
        val stream = stream(flowOf(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00)))
        assertEquals(listOf(255, 128, 0), stream.drainByteWise())
    }

    @Test
    fun emptyFlowReadsEndOfStreamImmediately() {
        val stream = stream(emptyFlow())
        assertEquals(-1, bounded { stream.read() })
        assertEquals(null, stream.bulk(8))
    }

    @Test
    fun closeBeforeConsumingReturnsEndOfStreamAndCancelsUpstream() {
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Throwable?>()
        val upstream = flow<ByteArray> {
            emit(byteArrayOf(1, 2, 3))
            started.complete(Unit)
            awaitCancellation()
        }.onCompletion { cause -> completion.complete(cause) }
        val stream = stream(upstream)
        awaitDeferred(started)

        stream.close()

        assertEquals(-1, bounded { stream.read() })
        assertEquals(null, stream.bulk(4))
        assertIs<CancellationException>(awaitDeferred(completion))
    }

    @Test
    fun moreChunksThanTheBufferHoldsLoseNothing() {
        val chunks = List(300) { byteArrayOf(it.toByte(), (it + 1).toByte()) }
        val stream = stream(flow { chunks.forEach { emit(it) } })
        val expected = chunks.flatMap { chunk -> chunk.map { it.toInt() and 0xFF } }
        assertEquals(expected, stream.drainByteWise())
    }

    @Test
    fun upstreamIsNotCollectedFurtherThanTheBufferAheadOfTheReader() {
        val total = 2_000
        val consumed = 10
        val emitted = AtomicInteger()
        val stream = stream(
            flow {
                repeat(total) {
                    emitted.incrementAndGet()
                    emit(byteArrayOf(it.toByte()))
                }
            }
        )

        repeat(consumed) { assertContentEquals(byteArrayOf(it.toByte()), stream.bulk(1)) }

        val emittedAfterReads = emitted.get()
        assertTrue(emittedAfterReads <= consumed + 66, "emitted $emittedAfterReads after $consumed reads")
        assertEquals(List(total - consumed) { (it + consumed).toByte().toInt() and 0xFF }, stream.drainByteWise())
        assertEquals(total, emitted.get())
    }

    @Test
    fun closeFromAnotherThreadEndsABlockedReadAndCancelsUpstream() {
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Throwable?>()
        val upstream = flow<ByteArray> {
            started.complete(Unit)
            awaitCancellation()
        }.onCompletion { cause -> completion.complete(cause) }
        val stream = stream(upstream)
        awaitDeferred(started)

        val readerStarted = CountDownLatch(1)
        val pending = CompletableFuture.supplyAsync {
            readerStarted.countDown()
            stream.read()
        }
        assertTrue(readerStarted.await(10, TimeUnit.SECONDS))
        assertFalse(pending.isDone)

        stream.close()

        assertEquals(-1, pending.get(10, TimeUnit.SECONDS))
        assertIs<CancellationException>(awaitDeferred(completion))
        assertEquals(-1, bounded { stream.read() })
    }

    @Test
    fun gatedUpstreamKeepsReadBlockedUntilReleased() {
        val gate = CompletableDeferred<Unit>()
        val stream = stream(
            flow {
                emit(byteArrayOf(1, 2, 3))
                gate.await()
                emit(byteArrayOf(4))
            }
        )
        assertContentEquals(byteArrayOf(1, 2, 3), stream.bulk(10))

        val readerStarted = CountDownLatch(1)
        val pending = CompletableFuture.supplyAsync {
            readerStarted.countDown()
            stream.read()
        }
        assertTrue(readerStarted.await(10, TimeUnit.SECONDS))
        assertFalse(pending.isDone)

        gate.complete(Unit)

        assertEquals(4, pending.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun readOfZeroLengthReturnsZero() {
        val stream = stream(flowOf(byteArrayOf(1, 2, 3)))
        assertEquals(0, bounded { stream.read(ByteArray(4), 0, 0) })
        assertContentEquals(byteArrayOf(1, 2, 3), stream.bulk(10))
    }

    @Test
    fun zeroLengthChunkIsSkippedByBulkRead() {
        val stream = stream(flowOf(byteArrayOf(), byteArrayOf(7)))
        val buffer = ByteArray(4)
        val count = bounded { stream.read(buffer, 0, 4) }
        assertEquals(1, count)
        assertEquals(7, buffer[0].toInt())
    }

    @Test
    fun zeroLengthChunkIsSkippedBySingleByteRead() {
        val stream = stream(flowOf(byteArrayOf(), byteArrayOf(7)))
        assertEquals(7, bounded { stream.read() })
        assertEquals(-1, bounded { stream.read() })
    }

    @Test
    fun upstreamFailureAfterFirstChunkIsSignalledAsIOException() {
        val upstreamFailure = UpstreamFailure()
        val stream = stream(
            flow {
                emit(byteArrayOf(1, 2))
                throw upstreamFailure
            }
        )
        val buffer = ByteArray(10)
        assertEquals(2, bounded { stream.read(buffer, 0, 10) })

        val failure = bounded {
            try {
                stream.read(buffer, 0, 10)
                null
            } catch (e: Throwable) {
                e
            }
        }

        assertIs<IOException>(failure)
        assertSame(upstreamFailure, failure.cause)
    }
}
