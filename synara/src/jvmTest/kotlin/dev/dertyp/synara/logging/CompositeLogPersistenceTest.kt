package dev.dertyp.synara.logging

import dev.dertyp.logging.LogLevel
import dev.dertyp.logging.LogPersistence
import dev.dertyp.logging.LogTag
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeLogPersistenceTest {
    private data class Call(
        val target: String,
        val tag: LogTag,
        val level: LogLevel,
        val message: String,
        val data: String?,
        val stacktrace: String?,
        val timestamp: Long,
    )

    private class RecordingTarget(private val name: String, private val calls: MutableList<Call>) : LogPersistence {
        override suspend fun persist(
            tag: LogTag,
            level: LogLevel,
            message: String,
            data: String?,
            stacktrace: String?,
            timestamp: Long
        ) {
            calls += Call(name, tag, level, message, data, stacktrace, timestamp)
        }
    }

    @Test
    fun everyTargetReceivesTheIdenticalArgumentsInOrder() = runBlocking {
        val calls = mutableListOf<Call>()
        val composite = CompositeLogPersistence(
            listOf(RecordingTarget("first", calls), RecordingTarget("second", calls), RecordingTarget("third", calls))
        )
        composite.persist(LogTag.RPC, LogLevel.ERROR, "message", "data", "trace", 1234L)
        assertEquals(
            listOf("first", "second", "third").map { Call(it, LogTag.RPC, LogLevel.ERROR, "message", "data", "trace", 1234L) },
            calls
        )
    }

    @Test
    fun nullDataAndStacktraceAreForwardedAsNull() = runBlocking {
        val calls = mutableListOf<Call>()
        val composite = CompositeLogPersistence(listOf(RecordingTarget("only", calls)))
        composite.persist(LogTag.SCROBBLER, LogLevel.INFO, "message", null, null, 5L)
        assertEquals(listOf(Call("only", LogTag.SCROBBLER, LogLevel.INFO, "message", null, null, 5L)), calls)
    }

    @Test
    fun consecutiveCallsAreForwardedInCallOrderToEachTarget() = runBlocking {
        val calls = mutableListOf<Call>()
        val composite = CompositeLogPersistence(listOf(RecordingTarget("a", calls), RecordingTarget("b", calls)))
        composite.persist(LogTag.RPC, LogLevel.INFO, "one", null, null, 1L)
        composite.persist(LogTag.RPC, LogLevel.WARNING, "two", null, null, 2L)
        assertEquals(listOf("a:one", "b:one", "a:two", "b:two"), calls.map { "${it.target}:${it.message}" })
    }

    @Test
    fun emptyTargetListDoesNotThrow() = runBlocking {
        CompositeLogPersistence(emptyList()).persist(LogTag.RPC, LogLevel.CRASH, "message", "data", "trace", 1L)
    }
}
