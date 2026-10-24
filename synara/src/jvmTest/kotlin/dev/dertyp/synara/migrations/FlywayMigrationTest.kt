package dev.dertyp.synara.migrations

import dev.dertyp.synara.db.DatabaseNotAtBaseException
import dev.dertyp.synara.db.DatabaseNotAtBaseException.Reason
import dev.dertyp.synara.db.LocalDatabaseMigration
import dev.dertyp.synara.db.MigrationBase
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteDataSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object MigrationProbeTable : Table("migration_probe") {
    val probeId = integer("probeId")

    override val primaryKey = PrimaryKey(probeId)
}

class FlywayMigrationTest {
    private val files = mutableListOf<File>()
    private val directories = mutableListOf<File>()

    private val oldMigrations = listOf("1") + (1..MigrationFiles.baseVersion).map { "1.$it" }
    private val laterMigrations = MigrationFiles.versioned(MigrationFiles.resources).map { it.version }
    private val headHistory = listOf(MigrationBase.VERSION to "SQL_BASELINE") + laterMigrations.map { "1.$it" to "SQL" }
    private val nextMigration = (laterMigrations + MigrationFiles.baseVersion).max() + 1

    private data class Snapshot(
        val tables: Set<String>,
        val schema: List<String>,
        val history: List<List<String?>>?,
        val users: List<List<String?>>?,
    )

    @AfterTest
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
        files.forEach { file ->
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
    }

    @Test
    fun anEmptyDatabaseGetsTheBaseAndASecondStartChangesNothing() {
        val target = emptyDatabase()

        LocalDatabaseMigration.migrate(target)

        val first = snapshot(target)
        assertEquals(headHistory, assertNotNull(first.history).map { it[1] to it[3] })
        assertEquals(
            SchemaTables.all.map { it.tableName }.toSet() + "flyway_schema_history",
            first.tables.filterNot { it.startsWith("sqlite_") }.toSet()
        )

        LocalDatabaseMigration.migrate(target)

        assertEquals(first, snapshot(target))
    }

    @Test
    fun aDatabaseUpgradedBy3170KeepsItsHistoryAndAppliesTheLaterMigrations() {
        val target = upgradedDatabase()
        val before = snapshot(target)
        val oldHistory = assertNotNull(before.history)
        assertEquals(oldMigrations, oldHistory.map { it[1] })
        val probe = probeMigration(nextMigration, MigrationProbeTable, target)

        withMigrations(probe) { LocalDatabaseMigration.migrate(target) }

        val after = snapshot(target)
        val history = assertNotNull(after.history)
        assertEquals(oldHistory, history.take(oldHistory.size))
        assertEquals(
            headHistory.drop(1) + ("1.$nextMigration" to "SQL"),
            history.drop(oldHistory.size).map { it[1] to it[3] }
        )
        assertEquals(setOf(MigrationProbeTable.tableName), after.tables - before.tables)
        assertEquals(before.users, after.users)

        withMigrations(probe) { LocalDatabaseMigration.migrate(target) }

        assertEquals(after, snapshot(target))
    }

    @Test
    fun aDatabaseUpgradedBy3170StartsWithTheMigrationsOfThisBuild() {
        val target = upgradedDatabase()
        val before = snapshot(target)

        LocalDatabaseMigration.migrate(target)

        val after = snapshot(target)
        assertEquals(before.history, assertNotNull(after.history).take(oldMigrations.size))
        assertEquals(headHistory.drop(1), assertNotNull(after.history).drop(oldMigrations.size).map { it[1] to it[3] })
        assertEquals(before.users, after.users)
    }

    @Test
    fun aDatabaseWhoseHistoryEndsBelowTheBaseIsRefused() {
        val target = upgradedDatabase(versions = oldMigrations.dropLast(1))

        assertRefused(target, Reason.BELOW_BASE, foundVersion = "1.${MigrationFiles.baseVersion - 1}")
    }

    @Test
    fun aDatabaseWithAFailedLastMigrationIsRefused() {
        val target = upgradedDatabase(failed = setOf(MigrationBase.VERSION))

        assertRefused(target, Reason.FAILED_MIGRATION, foundVersion = MigrationBase.VERSION)
    }

    @Test
    fun aDatabaseWithAMigrationThisBuildDoesNotContainIsRefused() {
        val target = upgradedDatabase(versions = oldMigrations + "1.$nextMigration")
        val probe = probeMigration(nextMigration + 1, MigrationProbeTable, target)

        withMigrations(probe) {
            assertRefused(target, Reason.MISSING_MIGRATION, foundVersion = "1.$nextMigration")
        }
    }

    @Test
    fun aDatabaseWithTablesButWithoutAHistoryTableIsRefused() {
        val target = upgradedDatabase()
        target.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE flyway_schema_history") }
        }
        assertNull(snapshot(target).history)

        assertRefused(target, Reason.TABLES_WITHOUT_HISTORY, foundVersion = null)
    }

    @Test
    fun aDatabaseWithTablesAndAnEmptyHistoryTableIsRefused() {
        val target = upgradedDatabase()
        target.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM flyway_schema_history") }
        }
        assertEquals(emptyList(), snapshot(target).history)

        assertRefused(target, Reason.TABLES_WITHOUT_HISTORY, foundVersion = null)
    }

    @Test
    fun aDatabaseThatHoldsOnlyAnEmptyHistoryTableGetsTheBase() {
        val target = emptyDatabase()
        val noMigrations = Files.createTempDirectory("no_migrations").toFile().also { directories += it }
        Flyway.configure()
            .dataSource(target)
            .locations("filesystem:${noMigrations.absolutePath}")
            .load()
            .migrate()
        val before = snapshot(target)
        assertEquals(setOf("flyway_schema_history"), before.tables)
        assertEquals(emptyList(), before.history)

        LocalDatabaseMigration.migrate(target)

        assertEquals(headHistory, assertNotNull(snapshot(target).history).map { it[1] to it[3] })
        assertEquals(emptyList(), withDatabase(target) { SchemaTables.all.filterNot { it.exists() } })
    }

    @Test
    fun anUpgradedDatabaseThatIsAheadOfThisBuildStarts() {
        val target = upgradedDatabase()
        LocalDatabaseMigration.migrate(target)
        insertHistory(target, firstRank = oldMigrations.size + headHistory.size, versions = listOf("1.$nextMigration"))
        val before = snapshot(target)
        assertEquals(
            oldMigrations + headHistory.drop(1).map { it.first } + "1.$nextMigration",
            before.history?.map { it[1] }
        )

        LocalDatabaseMigration.migrate(target)

        assertEquals(before, snapshot(target))
    }

    @Test
    fun aDatabaseCreatedFromTheBaseThatIsAheadOfThisBuildStarts() {
        val target = emptyDatabase()
        LocalDatabaseMigration.migrate(target)
        insertHistory(target, firstRank = headHistory.size + 1, versions = listOf("1.$nextMigration"))
        val before = snapshot(target)
        assertEquals(headHistory.map { it.first } + "1.$nextMigration", before.history?.map { it[1] })

        LocalDatabaseMigration.migrate(target)

        assertEquals(before, snapshot(target))
    }

    private fun assertRefused(target: SQLiteDataSource, reason: Reason, foundVersion: String?) {
        val before = snapshot(target)

        val refusal = assertFailsWith<DatabaseNotAtBaseException> { LocalDatabaseMigration.migrate(target) }

        assertEquals(reason, refusal.reason)
        assertEquals(foundVersion, refusal.foundVersion)
        val message = assertNotNull(refusal.message)
        assertTrue(message.contains(MigrationBase.STEPPING_STONE_RELEASE_URL), message)
        assertTrue(message.contains(reason.description), message)
        assertTrue(message.contains("Found schema version: ${foundVersion ?: "none"}"), message)
        Reason.entries.filter { it != reason }.forEach { other ->
            assertFalse(message.contains(other.description), message)
        }
        assertEquals(before, snapshot(target))
    }

    private fun emptyDatabase(): SQLiteDataSource {
        val file = File.createTempFile("flyway_test", ".db").also { files += it }
        return SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }
    }

    private fun upgradedDatabase(versions: List<String> = oldMigrations, failed: Set<String> = emptySet()): SQLiteDataSource {
        val target = emptyDatabase()
        Flyway.configure()
            .dataSource(target)
            .locations(MigrationBase.LOCATION)
            .target(MigrationBase.VERSION)
            .placeholderReplacement(false)
            .load()
            .migrate()
        target.connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM flyway_schema_history")
                it.executeUpdate(
                    "INSERT INTO \"user\" (id, username, displayName, passwordHash, isAdmin, server) " +
                        "VALUES (X'00112233445566778899AABBCCDDEEFF', 'listener', 'Listener', '', 0, 'synara.test')"
                )
            }
        }
        insertHistory(target, firstRank = 1, versions = versions, failed = failed)
        return target
    }

    private fun insertHistory(target: SQLiteDataSource, firstRank: Int, versions: List<String>, failed: Set<String> = emptySet()) {
        target.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO flyway_schema_history " +
                    "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                    "VALUES (?, ?, ?, 'JDBC', ?, NULL, '', ?, ?)"
            ).use { statement ->
                versions.forEachIndexed { index, version ->
                    val name = "V${version.replace('.', '_')}__Step"
                    statement.setInt(1, firstRank + index)
                    statement.setString(2, version)
                    statement.setString(3, "Step")
                    statement.setString(4, "dev.dertyp.synara.db.migration.$name")
                    statement.setInt(5, 5)
                    statement.setBoolean(6, version !in failed)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun probeMigration(version: Int, table: Table, target: SQLiteDataSource): File {
        val root = Files.createTempDirectory("probe_migrations").toFile().also { directories += it }
        val folder = File(root, MigrationBase.LOCATION.removePrefix("classpath:")).also { it.mkdirs() }
        val statements = withDatabase(target) { table.ddl }
        File(folder, MigrationFiles.fileName(version, "Probe")).writeText(statements.joinToString("") { "$it;\n" })
        return root
    }

    private fun <T> withMigrations(root: File, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return URLClassLoader(arrayOf(root.toURI().toURL()), previous).use { loader ->
            thread.contextClassLoader = loader
            try {
                block()
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    private fun <T> withDatabase(target: SQLiteDataSource, block: () -> T): T {
        val database = Database.connect(target)
        return try {
            transaction(database) { block() }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun snapshot(target: SQLiteDataSource): Snapshot = target.connection.use { connection ->
        val tables = connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
            buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
        }
        val schema = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT type || ' ' || name || ' ' || coalesce(sql, '') FROM sqlite_master ORDER BY type, name")
                .use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        Snapshot(
            tables = tables,
            schema = schema,
            history = rows(connection, tables, "flyway_schema_history", "installed_rank"),
            users = rows(connection, tables, "user", "id"),
        )
    }

    private fun rows(connection: Connection, tables: Set<String>, table: String, order: String): List<List<String?>>? {
        if (table !in tables) return null
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"$table\" ORDER BY $order").use { result ->
                buildList {
                    while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                }
            }
        }
    }
}
