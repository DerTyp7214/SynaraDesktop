package dev.dertyp.synara.logging

import dev.dertyp.logging.LogLevel
import dev.dertyp.logging.LogPersistence
import dev.dertyp.logging.LogTag
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

class FileLogPersistence(private val dataDir: () -> String) : LogPersistence {
    private val mutex = Mutex()
    private val logFile: File by lazy { File(File(dataDir(), "logs").also { it.mkdirs() }, "synara.log") }

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
            LogLevel.CRASH -> "CRASH"
        }
        val text = buildString {
            append("${Instant.ofEpochMilli(timestamp)} [$levelStr] [$tag] $message\n")
            if (data != null) {
                append("  Data: $data\n")
            }
            if (stacktrace != null && level == LogLevel.ERROR) {
                append("  Stacktrace: $stacktrace\n")
            }
        }
        withContext(AppDispatchers.io) {
            mutex.withLock {
                try {
                    val file = logFile
                    if (file.length() + text.length > MAX_BYTES) {
                        rotate(file)
                    }
                    file.appendText(text)
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun rotate(file: File) {
        File(file.parentFile, "${file.name}.${KEPT_FILES - 1}").delete()
        for (index in KEPT_FILES - 2 downTo 1) {
            val older = File(file.parentFile, "${file.name}.$index")
            if (older.exists()) {
                older.renameTo(File(file.parentFile, "${file.name}.${index + 1}"))
            }
        }
        file.renameTo(File(file.parentFile, "${file.name}.1"))
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val KEPT_FILES = 3
    }
}
