package dev.dertyp.synara.db

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposedSettingsSyncRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedSettingsSyncRepository

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-settings-sync", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) { SchemaUtils.create(SettingsSyncKnownEntries) }
        repository = ExposedSettingsSyncRepository()
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    @Test
    fun emptyRepositoryHasNoEntries() = runBlocking {
        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun storedEntriesComeBackWithTheirValueAndVersion() = runBlocking {
        repository.putAll(
            mapOf(
                "theme" to SettingsSyncKnown("dark", 4),
                "volume" to SettingsSyncKnown("0.5", 9)
            )
        )

        assertEquals(
            mapOf(
                "theme" to SettingsSyncKnown("dark", 4),
                "volume" to SettingsSyncKnown("0.5", 9)
            ),
            repository.getAll()
        )
    }

    @Test
    fun puttingAKeyAgainReplacesItsValueAndVersion() = runBlocking {
        repository.putAll(mapOf("theme" to SettingsSyncKnown("dark", 1)))

        repository.putAll(mapOf("theme" to SettingsSyncKnown("light", 2)))

        assertEquals(mapOf("theme" to SettingsSyncKnown("light", 2)), repository.getAll())
    }

    @Test
    fun puttingOneKeyLeavesTheOthersUntouched() = runBlocking {
        repository.putAll(mapOf("a" to SettingsSyncKnown("1", 1), "b" to SettingsSyncKnown("2", 2)))

        repository.putAll(mapOf("a" to SettingsSyncKnown("3", 3)))

        assertEquals(
            mapOf("a" to SettingsSyncKnown("3", 3), "b" to SettingsSyncKnown("2", 2)),
            repository.getAll()
        )
    }

    @Test
    fun nullValueRoundTripsAsNullAndDiffersFromEmptyString() = runBlocking {
        repository.putAll(mapOf("cleared" to SettingsSyncKnown(null, 3), "empty" to SettingsSyncKnown("", 4)))

        val stored = repository.getAll()

        assertEquals(null, stored.getValue("cleared").value)
        assertEquals("", stored.getValue("empty").value)
        assertEquals(3L, stored.getValue("cleared").version)
    }

    @Test
    fun puttingNothingChangesNothing() = runBlocking {
        repository.putAll(mapOf("a" to SettingsSyncKnown("1", 1)))

        repository.putAll(emptyMap())

        assertEquals(mapOf("a" to SettingsSyncKnown("1", 1)), repository.getAll())
    }

    @Test
    fun removeDeletesOnlyTheGivenKeys() = runBlocking {
        repository.putAll(
            mapOf("a" to SettingsSyncKnown("1", 1), "b" to SettingsSyncKnown("2", 2), "c" to SettingsSyncKnown("3", 3))
        )

        repository.remove(listOf("a", "c"))

        assertEquals(mapOf("b" to SettingsSyncKnown("2", 2)), repository.getAll())
    }

    @Test
    fun removingMoreKeysThanOneChunkHoldsDeletesAllOfThem() = runBlocking {
        val keys = (0 until 1200).map { "key-$it" }
        repository.putAll(keys.associateWith { SettingsSyncKnown("v-$it", 1) } + ("keeper" to SettingsSyncKnown("kept", 7)))

        repository.remove(keys)

        assertEquals(mapOf("keeper" to SettingsSyncKnown("kept", 7)), repository.getAll())
    }

    @Test
    fun removingAbsentKeysOrNoKeysChangesNothing() = runBlocking {
        repository.putAll(mapOf("a" to SettingsSyncKnown("1", 1)))

        repository.remove(listOf("missing", "also-missing"))
        repository.remove(emptyList())
        repository.remove(emptySet())

        assertEquals(mapOf("a" to SettingsSyncKnown("1", 1)), repository.getAll())
    }

    @Test
    fun clearEmptiesTheRepository() = runBlocking {
        repository.putAll(mapOf("a" to SettingsSyncKnown("1", 1), "b" to SettingsSyncKnown(null, 2)))

        repository.clear()

        assertTrue(repository.getAll().isEmpty())
    }

    @Test
    fun clearOnAnEmptyRepositoryIsFine() = runBlocking {
        repository.clear()

        assertTrue(repository.getAll().isEmpty())
    }
}
