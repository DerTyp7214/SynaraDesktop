package dev.dertyp.synara.settings

import dev.dertyp.data.BaseSong
import dev.dertyp.logging.Logger
import dev.dertyp.synara.services.LocalStorageService
import io.mockk.mockk
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlFileSettingsTest {

    private class TempStorageService(private val dir: Path) : LocalStorageService {
        override fun getConfigDir(): String = dir.toString()
        override fun getCacheDir(): String = dir.toString()
        override fun getDataDir(): String = dir.toString()
        override fun getSystemMusicDir(): String = dir.toString()
        override fun getInternalSongDir(): String = dir.toString()
        override fun getInternalSongPath(song: BaseSong): String = dir.toString()
        override fun linkSongToSystemMusicDir(song: BaseSong) = Unit
        override fun unlinkSongFromSystemMusicDir(song: BaseSong) = Unit
    }

    private val dir: Path = Files.createTempDirectory("synara-settings-test")
    private val configFile: Path = dir.resolve("config.yml")
    private val authFile: Path = dir.resolve("auth.yml")
    private val logger = mockk<Logger>(relaxed = true)

    private fun open(): PersistentSettings = SettingsFactory(TempStorageService(dir), logger).create()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `strings that look like numbers or booleans survive a reload`() {
        val values = mapOf(
            SettingKey.QueueSyncDeviceName to "2",
            SettingKey.AudioOutputDevice to "true",
            SettingKey.LastFmUsername to "007",
            SettingKey.LastFmSessionKey to "1e5",
            SettingKey.ListenBrainzToken to "12345"
        )
        val settings = open()
        values.forEach { (key, value) -> settings.putString(key.name, value) }
        settings.flush()

        val reloaded = open()
        values.forEach { (key, value) ->
            assertEquals(value, reloaded.getStringOrNull(key.name))
            assertEquals(value, reloaded.getOrNull(key))
        }
        assertTrue(Files.readString(authFile).contains(SettingKey.LastFmSessionKey.name))
        assertFalse(Files.readString(configFile).contains(SettingKey.LastFmSessionKey.name))
    }

    @Test
    fun `typed values survive a reload`() {
        val settings = open()
        settings.putInt(SettingKey.Port.name, 8080)
        settings.putBoolean(SettingKey.Ssl.name, true)
        settings.putBoolean(SettingKey.DarkTheme.name, false)
        settings.putLong(SettingKey.TokenExpiration.name, 1_700_000_000_000L)
        settings.putFloat(SettingKey.Volume.name, 0.35f)
        settings.putDouble("double_value", 2.5)
        settings.flush()

        val reloaded = open()
        assertEquals(8080, reloaded.getInt(SettingKey.Port.name, 0))
        assertEquals(8080, reloaded.getOrNull(SettingKey.Port))
        assertEquals(true, reloaded.getBooleanOrNull(SettingKey.Ssl.name))
        assertEquals(false, reloaded.getBoolean(SettingKey.DarkTheme.name, true))
        assertEquals(1_700_000_000_000L, reloaded.getLong(SettingKey.TokenExpiration.name, 0L))
        assertEquals(0.35f, reloaded.getFloat(SettingKey.Volume.name, 0f))
        assertEquals(2.5, reloaded.getDouble("double_value", 0.0))
        assertNull(reloaded.getIntOrNull("missing"))
        assertEquals(7, reloaded.getInt("missing", 7))
    }

    @Test
    fun `remove and clear are persisted`() {
        val settings = open()
        settings.putString(SettingKey.Host.name, "example.org")
        settings.putInt(SettingKey.Port.name, 1)
        settings.putString(SettingKey.AuthToken.name, "token")
        settings.remove(SettingKey.Port.name)
        settings.flush()

        val reloaded = open()
        assertEquals(setOf(SettingKey.Host.name, SettingKey.AuthToken.name), reloaded.keys)
        assertFalse(reloaded.hasKey(SettingKey.Port.name))

        reloaded.clear()
        reloaded.flush()
        assertEquals(0, open().size)
    }

    @Test
    fun `concurrent writers leave the last written values on disk`() {
        val threads = 8
        val writes = 200
        val sharedKeys = listOf("shared", SettingKey.TokenExpiration.name)
        val settings = open()
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { index ->
            thread {
                start.await()
                repeat(writes) { round ->
                    settings.putInt("thread_$index", round)
                    settings.putInt(sharedKeys[round % sharedKeys.size], index * writes + round)
                    settings.keys
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        settings.flush()

        val reloaded = open()
        repeat(threads) { index ->
            assertEquals(writes - 1, reloaded.getIntOrNull("thread_$index"))
        }
        sharedKeys.forEach { key ->
            assertEquals(settings.getIntOrNull(key), reloaded.getIntOrNull(key))
        }
        assertEquals(settings.keys, reloaded.keys)
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `flush writes a value that arrived while a write was running`() {
        val settings = open()
        repeat(100) { round ->
            repeat(25) { step -> settings.putInt(SettingKey.Port.name, round * 100 + step) }
            settings.putString(SettingKey.AuthToken.name, "token-$round")
            settings.flush()

            val reloaded = open()
            assertEquals(round * 100 + 24, reloaded.getIntOrNull(SettingKey.Port.name))
            assertEquals("token-$round", reloaded.getStringOrNull(SettingKey.AuthToken.name))
        }
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `files are readable by the owner only`() {
        assumeTrue("posix" in dir.fileSystem.supportedFileAttributeViews())
        val ownerOnly = PosixFilePermissions.fromString("rw-------")
        Files.writeString(configFile, "host: \"example.org\"\n")
        Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-r--r--"))

        val settings = open()
        assertEquals(ownerOnly, Files.getPosixFilePermissions(configFile))
        assertEquals("example.org", settings.getStringOrNull(SettingKey.Host.name))

        settings.putInt(SettingKey.Port.name, 443)
        settings.putString(SettingKey.AuthToken.name, "token")
        settings.flush()

        assertEquals(ownerOnly, Files.getPosixFilePermissions(configFile))
        assertEquals(ownerOnly, Files.getPosixFilePermissions(authFile))
    }

    private fun tempFiles(): List<String> =
        dir.toFile().listFiles().orEmpty().map(File::getName).filter { it.endsWith(".tmp") }
}
