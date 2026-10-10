package dev.dertyp.synara.logging

import dev.dertyp.logging.LogLevel
import dev.dertyp.logging.LogTag
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileLogPersistenceTest {
    private val dir: File = Files.createTempDirectory("synara-log-test").toFile()
    private val tag = LogTag("test")
    private val chunk = "x".repeat(1024 * 1024)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun logsDir() = File(dir, "logs")

    @Test
    fun appendsTimestampedLines() = runBlocking {
        val persistence = FileLogPersistence { dir.absolutePath }
        persistence.persist(tag, LogLevel.WARNING, "hello", "payload", null, 0L)
        persistence.persist(tag, LogLevel.ERROR, "boom", null, "trace", 1_000L)
        val text = File(logsDir(), "synara.log").readText()
        assertEquals(
            "1970-01-01T00:00:00Z [WARN] [test] hello\n  Data: payload\n1970-01-01T00:00:01Z [ERROR] [test] boom\n  Stacktrace: trace\n",
            text
        )
    }

    @Test
    fun rotatesAfterTheSizeLimitAndKeepsThreeFiles() = runBlocking {
        val persistence = FileLogPersistence { dir.absolutePath }
        repeat(30) { persistence.persist(tag, LogLevel.INFO, chunk, null, null, 0L) }
        val names = logsDir().list().orEmpty().toSet()
        assertEquals(setOf("synara.log", "synara.log.1", "synara.log.2"), names)
        logsDir().listFiles().orEmpty().forEach { assertTrue(it.length() <= 5L * 1024 * 1024, it.name) }
        assertFalse(File(logsDir(), "synara.log.3").exists())
    }
}
