package dev.dertyp.synara.logging

import dev.dertyp.logging.LogLevel
import dev.dertyp.logging.LogPersistence
import dev.dertyp.logging.LogTag

class CompositeLogPersistence(private val targets: List<LogPersistence>) : LogPersistence {
    override suspend fun persist(
        tag: LogTag,
        level: LogLevel,
        message: String,
        data: String?,
        stacktrace: String?,
        timestamp: Long
    ) {
        targets.forEach { it.persist(tag, level, message, data, stacktrace, timestamp) }
    }
}
