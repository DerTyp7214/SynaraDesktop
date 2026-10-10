package dev.dertyp.synara.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.PlatformUUID
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.*
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1_13__UserServerTest {
    private class LegacyUsers(usernameIndex: String?) : UUIDTable("user") {
        val username = varchar("username", 255).uniqueIndex(usernameIndex)
        val displayName = varchar("displayName", 255).nullable()
        val passwordHash = varchar("passwordHash", 255)
        val isAdmin = bool("isAdmin").default(false)
        val profileImage = reference("profileImageId", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    }

    private lateinit var file: Path
    private lateinit var referenceFile: Path
    private lateinit var url: String
    private lateinit var referenceUrl: String
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var reference: Database

    private val userTable = DownloadedUsers.tableName
    private val legacyColumns = listOf("id", "username", "displayName", "passwordHash", "isAdmin", "profileImageId")
    private val filledTables = listOf(DownloadedImages, DownloadedUserPlaylists, ScrobbleQueue, LocalHistory).map { it.tableName }

    private val allTables: Array<Table> = arrayOf(
        DownloadedImages,
        DownloadedUsers,
        DownloadedSongs,
        DownloadedAlbums,
        DownloadedArtists,
        DownloadedGenres,
        DownloadedPlaylists,
        DownloadedUserPlaylists,
        DownloadedSongArtists,
        DownloadedAlbumArtists,
        DownloadedArtistMembers,
        DownloadedPlaylistSongs,
        DownloadedUserPlaylistSongs,
        DownloadedSongGenres,
        DownloadedAlbumGenres,
        DownloadedArtistGenres,
        RecentlyPlayedSongs,
        RecentlyPlayedAlbums,
        RecentlyPlayedArtists,
        ScrobbleQueue,
        LocalHistory,
        SongGuessGames,
        SongGuessSavedGames,
        SettingsSyncKnownEntries
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration-user-server", ".db")
        referenceFile = Files.createTempFile("synara-migration-user-server-reference", ".db")
        url = "jdbc:sqlite:$file"
        referenceUrl = "jdbc:sqlite:$referenceFile"
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "5000")
            }
        )
        startTestKoin(module { single { dataSource } })
        database = Database.connect(dataSource)
        reference = Database.connect(referenceUrl, driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(reference) { SchemaUtils.create(*allTables) }
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        TransactionManager.closeAndUnregister(reference)
        dataSource.close()
        stopTestKoin()
        file.deleteIfExists()
        file.resolveSibling("${file.fileName}-wal").deleteIfExists()
        file.resolveSibling("${file.fileName}-shm").deleteIfExists()
        referenceFile.deleteIfExists()
    }

    private fun migrate(target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:dev/dertyp/synara/db/migration")
            .baselineOnMigrate(true)
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    private fun runMigration(url: String) {
        DriverManager.getConnection(url).use { connection ->
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_13__UserServer().migrate(context)
        }
    }

    private fun query(connection: Connection, sql: String): List<List<String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(List(rs.metaData.columnCount) { rs.getString(it + 1) }) }
            }
        }

    private fun query(url: String, sql: String): List<List<String?>> =
        DriverManager.getConnection(url).use { query(it, sql) }

    private fun columns(url: String, table: String): List<List<String?>> =
        query(url, "PRAGMA table_info(\"$table\")").map { it.drop(1) }

    private fun foreignKeys(url: String, table: String): Set<List<String?>> =
        query(url, "PRAGMA foreign_key_list(\"$table\")").mapTo(HashSet()) { it.drop(2) }

    private fun indices(url: String, table: String): Set<List<Any?>> =
        query(url, "PRAGMA index_list(\"$table\")").mapTo(HashSet()) { index ->
            val indexed = query(url, "PRAGMA index_info(\"${index[1]}\")").map { it[2] }
            index.drop(1) + listOf(indexed)
        }

    private fun rows(url: String, table: String, columns: List<String?> = columns(url, table).map { it[0] }): List<String> {
        val quoted = columns.joinToString(", ") { "quote(\"$it\")" }
        return query(url, "SELECT $quoted FROM \"$table\"").map { it.toString() }.sorted()
    }

    private fun filledRows(url: String): Map<String, List<String>> = filledTables.associateWith { rows(url, it) }

    private fun schema(url: String): List<List<String?>> =
        query(url, "SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name")

    private fun downgradeUserTable(usernameIndex: String?): LegacyUsers {
        val legacy = LegacyUsers(usernameIndex)
        transaction(database) { SchemaUtils.drop(DownloadedUsers) }
        transaction(database) { SchemaUtils.create(legacy) }
        return legacy
    }

    private fun fill(legacy: LegacyUsers): List<PlatformUUID> {
        val image = randomPlatformUUID()
        val first = randomPlatformUUID()
        val second = randomPlatformUUID()
        transaction(database) {
            DownloadedImages.insert {
                it[id] = image
                it[path] = "/avatar.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
            legacy.insert {
                it[legacy.id] = first
                it[legacy.username] = "mac"
                it[legacy.displayName] = "It's \"Mac\""
                it[legacy.passwordHash] = ""
                it[legacy.isAdmin] = true
                it[legacy.profileImage] = image
            }
            legacy.insert {
                it[legacy.id] = second
                it[legacy.username] = "other"
                it[legacy.passwordHash] = ""
            }
            listOf(first, second).forEachIndexed { index, user ->
                DownloadedUserPlaylists.insert {
                    it[id] = randomPlatformUUID()
                    it[name] = "Playlist $index"
                    it[creator] = user
                }
                ScrobbleQueue.insert {
                    it[userId] = user
                    it[songId] = "song-$index"
                    it[timestamp] = index.toLong()
                    it[target] = "server"
                    it[payload] = "scrobble-$index"
                }
                LocalHistory.insert {
                    it[userId] = user
                    it[songId] = "song-$index"
                    it[timestamp] = index.toLong()
                    it[payload] = "history-$index"
                }
            }
        }
        return listOf(first, second)
    }

    private fun insertUser(name: String, from: String?) = transaction(database) {
        DownloadedUsers.insert {
            it[id] = randomPlatformUUID()
            it[username] = name
            it[passwordHash] = ""
            it[server] = from
        }
    }

    private fun assertUserTableMatchesTheDefinition() {
        assertEquals(columns(referenceUrl, userTable), columns(url, userTable))
        assertEquals(foreignKeys(referenceUrl, userTable), foreignKeys(url, userTable))
        assertEquals(indices(referenceUrl, userTable), indices(url, userTable))
        assertEquals(emptyList(), transaction(database) { SchemaUtils.statementsRequiredToActualizeScheme(DownloadedUsers) })
    }

    @Test
    fun theUserTableBeforeTheMigrationHasNoServerAndAllowsAUsernameOnce() {
        migrate(target = "1.12")
        val legacy = downgradeUserTable(null)
        fill(legacy)

        val duplicate = runCatching {
            transaction(database) {
                legacy.insert {
                    it[legacy.id] = randomPlatformUUID()
                    it[legacy.username] = "mac"
                    it[legacy.passwordHash] = ""
                }
            }
        }

        assertEquals(legacyColumns, columns(url, userTable).map { it[0] })
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun anUpgradedUserTableHasTheColumnsReferencesAndIndicesOfTheExposedDefinition() {
        migrate(target = "1.12")
        fill(downgradeUserTable(null))

        migrate()

        assertUserTableMatchesTheDefinition()
    }

    @Test
    fun aUsernameIndexUnderAnotherNameIsReplacedToo() {
        migrate(target = "1.12")
        fill(downgradeUserTable("user_username_unique"))

        migrate()

        assertUserTableMatchesTheDefinition()
    }

    @Test
    fun noRowChangesAndEveryExistingUserHasAnUnknownServer() {
        migrate(target = "1.12")
        fill(downgradeUserTable(null))
        val usersBefore = rows(url, userTable)
        val othersBefore = filledRows(url)
        assertEquals(2, usersBefore.size)
        assertTrue(othersBefore.values.all { it.isNotEmpty() })

        migrate()

        assertEquals(usersBefore, rows(url, userTable, legacyColumns))
        assertEquals(listOf(listOf<String?>(null), listOf<String?>(null)), query(url, "SELECT \"server\" FROM \"$userTable\""))
        assertEquals(othersBefore, filledRows(url))
    }

    @Test
    fun afterTheMigrationAUsernameCanExistOncePerServerAndBesideUnknownServers() {
        migrate(target = "1.12")
        fill(downgradeUserTable(null))
        migrate()

        insertUser("mac", "first.example:8080")
        insertUser("mac", "second.example:8080")
        insertUser("mac", null)
        val duplicate = runCatching { insertUser("mac", "first.example:8080") }

        assertTrue(duplicate.isFailure)
        assertEquals(
            listOf<List<String?>>(listOf(null), listOf(null), listOf("first.example:8080"), listOf("second.example:8080")),
            query(url, "SELECT \"server\" FROM \"$userTable\" WHERE \"username\" = 'mac' ORDER BY \"server\"")
        )
    }

    @Test
    fun aSecondRunLeavesTheSchemaAndTheRowsUntouched() {
        migrate(target = "1.12")
        fill(downgradeUserTable(null))
        migrate()
        insertUser("mac", "first.example:8080")
        val schemaBefore = schema(url)
        val usersBefore = rows(url, userTable)
        val othersBefore = filledRows(url)

        runMigration(url)

        assertEquals(schemaBefore, schema(url))
        assertEquals(usersBefore, rows(url, userTable))
        assertEquals(othersBefore, filledRows(url))
    }

    @Test
    fun aFreshlyMigratedDatabaseAlreadyMatchesTheDefinitionAndIsLeftUntouched() {
        migrate()
        insertUser("mac", "first.example:8080")
        insertUser("mac", null)
        val schemaBefore = schema(url)
        val usersBefore = rows(url, userTable)
        assertUserTableMatchesTheDefinition()

        runMigration(url)

        assertEquals(schemaBefore, schema(url))
        assertEquals(usersBefore, rows(url, userTable))
    }

    @Test
    fun aDatabaseCreatedFromTheDefinitionsIsLeftUntouched() {
        val schemaBefore = schema(referenceUrl)

        runMigration(referenceUrl)

        assertEquals(schemaBefore, schema(referenceUrl))
    }

    @Test
    fun aDatabaseWithoutAUserTableIsLeftUntouched() {
        val emptyFile = Files.createTempFile("synara-migration-user-server-empty", ".db")
        try {
            val emptyUrl = "jdbc:sqlite:$emptyFile"

            runMigration(emptyUrl)

            assertEquals(emptyList(), schema(emptyUrl))
        } finally {
            emptyFile.deleteIfExists()
        }
    }
}
