package dev.dertyp.synara.logging

import dev.dertyp.logging.LogLevel
import dev.dertyp.logging.LogPersistence
import dev.dertyp.logging.LogTag

class StdoutLogPersistence : LogPersistence {
    override suspend fun persist(
        tag: LogTag,
        level: LogLevel,
        message: String,
        data: String?,
        stacktrace: String?,
        timestamp: Long
    ) {
        val levelStr = when (level) {
            LogLevel.INFO -> "INFO"
            LogLevel.WARNING -> "WARN"
            LogLevel.ERROR -> "ERROR"
        }
        println("[$levelStr] [$tag] $message")
        if (data != null) {
            println("  Data: $data")
        }
        if (stacktrace != null && level == LogLevel.ERROR) {
            println("  Stacktrace: $stacktrace")
        }
    }
}
