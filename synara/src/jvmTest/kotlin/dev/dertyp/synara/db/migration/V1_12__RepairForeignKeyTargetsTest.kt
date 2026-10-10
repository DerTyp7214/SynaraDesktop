package dev.dertyp.synara.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.*
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Table
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
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V1_12__RepairForeignKeyTargetsTest {
    private lateinit var file: Path
    private lateinit var referenceFile: Path
    private lateinit var url: String
    private lateinit var referenceUrl: String
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var reference: Database

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

    private val rebuiltTables = listOf(
        DownloadedSongs,
        DownloadedSongArtists,
        DownloadedAlbumArtists,
        DownloadedArtistMembers,
        DownloadedPlaylistSongs,
        DownloadedSongGenres,
        DownloadedAlbumGenres,
        DownloadedArtistGenres
    ).map { it.tableName }

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration-foreign-keys", ".db")
        referenceFile = Files.createTempFile("synara-migration-foreign-keys-reference", ".db")
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

    private fun runMigration(url: String, enforceForeignKeys: Boolean = false): Boolean =
        DriverManager.getConnection(url).use { connection ->
            if (enforceForeignKeys) connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_12__RepairForeignKeyTargets().migrate(context)
            query(connection, "PRAGMA foreign_keys").single().single() == "1"
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

    private fun rows(url: String, table: String): List<String> {
        val quoted = columns(url, table).joinToString(", ") { "quote(\"${it[0]}\")" }
        return query(url, "SELECT $quoted FROM \"$table\"").map { it.toString() }.sorted()
    }

    private fun allRows(url: String): Map<String, List<String>> =
        allTables.associate { it.tableName to rows(url, it.tableName) }

    private fun schema(url: String): List<List<String?>> =
        query(url, "SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name")

    private fun danglingReferences(url: String): Set<Pair<String, String?>> {
        val tables = query(url, "SELECT name FROM sqlite_master WHERE type = 'table'").mapTo(HashSet()) { it[0] }
        return tables.flatMapTo(HashSet()) { table ->
            query(url, "PRAGMA foreign_key_list(\"$table\")").map { table!! to it[2] }
        }.filterTo(HashSet()) { (_, target) -> target !in tables }
    }

    private fun fill(target: Database) {
        val image = randomPlatformUUID()
        val user = randomPlatformUUID()
        val firstAlbum = randomPlatformUUID()
        val secondAlbum = randomPlatformUUID()
        val solo = randomPlatformUUID()
        val group = randomPlatformUUID()
        val genre = randomPlatformUUID()
        val playlist = randomPlatformUUID()
        val userPlaylist = randomPlatformUUID()
        val firstSong = randomPlatformUUID()
        val secondSong = randomPlatformUUID()
        transaction(target) {
            DownloadedImages.insert {
                it[id] = image
                it[path] = "/cover.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
            DownloadedUsers.insert {
                it[id] = user
                it[username] = "mac"
                it[passwordHash] = ""
            }
            listOf(firstAlbum to "First", secondAlbum to "Second").forEach { (album, title) ->
                DownloadedAlbums.insert {
                    it[id] = album
                    it[name] = title
                    it[songCount] = 2
                }
            }
            DownloadedArtists.insert {
                it[id] = solo
                it[name] = "Solo"
            }
            DownloadedArtists.insert {
                it[id] = group
                it[name] = "Group"
                it[isGroup] = true
            }
            DownloadedGenres.insert {
                it[id] = genre
                it[name] = "Rap"
            }
            DownloadedPlaylists.insert {
                it[id] = playlist
                it[name] = "Playlist"
            }
            DownloadedUserPlaylists.insert {
                it[id] = userPlaylist
                it[name] = "Mine"
                it[creator] = user
            }
            DownloadedSongs.insert {
                it[id] = firstSong
                it[title] = "It's \"quoted\""
                it[tags] = "[{\"type\":\"LIVE\"}]"
                it[albumId] = firstAlbum
                it[duration] = 123_456L
                it[releaseDate] = "2020-01-17"
                it[lyrics] = "line one\nline two"
                it[explicit] = true
                it[filePath] = "/music/first.flac"
                it[cover] = image
                it[originalUrl] = "https://example.org/first"
                it[trackNumber] = 7
                it[discNumber] = 2
                it[copyright] = "(c)"
                it[codec] = "flac"
                it[sampleRate] = 96_000
                it[bitsPerSample] = 24
                it[bitRate] = 2_800_000L
                it[fileSize] = 55_555_555L
                it[channels] = 2
                it[audioStartMs] = 40L
                it[inserted] = 1_700_000_000_000L
                it[isFavourite] = true
                it[superLikedAt] = 1_700_000_000_001L
                it[createdAt] = 1_700_000_000_002L
                it[updatedAt] = 1_700_000_000_003L
                it[explicitlySaved] = true
                it[musicBrainzId] = UUID.randomUUID()
            }
            DownloadedSongs.insert {
                it[id] = secondSong
            }
            DownloadedSongArtists.insert {
                it[songId] = firstSong
                it[artistId] = solo
            }
            DownloadedSongArtists.insert {
                it[songId] = firstSong
                it[artistId] = group
            }
            DownloadedSongArtists.insert {
                it[songId] = secondSong
                it[artistId] = group
            }
            DownloadedAlbumArtists.insert {
                it[albumId] = firstAlbum
                it[artistId] = solo
            }
            DownloadedAlbumArtists.insert {
                it[albumId] = secondAlbum
                it[artistId] = group
            }
            DownloadedArtistMembers.insert {
                it[groupId] = group
                it[memberId] = solo
            }
            DownloadedPlaylistSongs.insert {
                it[playlistId] = playlist
                it[songId] = firstSong
                it[position] = 3
            }
            DownloadedPlaylistSongs.insert {
                it[playlistId] = playlist
                it[songId] = secondSong
            }
            DownloadedUserPlaylistSongs.insert {
                it[playlistId] = userPlaylist
                it[songId] = firstSong
                it[addedAt] = 1_700_000_000_004L
                it[musicBrainzId] = UUID.randomUUID()
            }
            DownloadedSongGenres.insert {
                it[songId] = firstSong
                it[genreId] = genre
            }
            DownloadedAlbumGenres.insert {
                it[albumId] = firstAlbum
                it[genreId] = genre
            }
            DownloadedArtistGenres.insert {
                it[artistId] = solo
                it[genreId] = genre
            }
        }
    }

    @Test
    fun theSchemaBeforeTheMigrationReferencesDroppedTablesFromTheRebuiltTables() {
        migrate(target = "1.11")

        assertEquals(rebuiltTables.toSet(), danglingReferences(url).mapTo(HashSet()) { it.first })
    }

    @Test
    fun noReferenceIsLeftDanglingAndNoRowOfAnyTableChanges() {
        migrate(target = "1.11")
        fill(database)
        val before = allRows(url)
        assertTrue(rebuiltTables.all { before.getValue(it).isNotEmpty() })

        migrate()

        assertEquals(emptySet(), danglingReferences(url))
        assertEquals(before, allRows(url))
    }

    @Test
    fun rebuiltTablesHaveTheColumnsReferencesAndIndicesOfTheExposedDefinitions() {
        migrate()

        for (table in rebuiltTables) {
            assertEquals(columns(referenceUrl, table), columns(url, table), table)
            assertEquals(foreignKeys(referenceUrl, table), foreignKeys(url, table), table)
            assertEquals(indices(referenceUrl, table), indices(url, table), table)
        }
    }

    @Test
    fun noReplacementTableIsLeftBehind() {
        migrate()

        val tables = query(url, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }
        assertEquals(emptyList(), tables.filter { it!!.endsWith("_rebuilt") })
    }

    @Test
    fun aSecondRunLeavesTheSchemaAndTheRowsUntouched() {
        migrate()
        fill(database)
        val schemaBefore = schema(url)
        val rowsBefore = allRows(url)

        runMigration(url)

        assertEquals(schemaBefore, schema(url))
        assertEquals(rowsBefore, allRows(url))
    }

    @Test
    fun aDatabaseWhoseReferencesAreIntactIsLeftUntouched() {
        fill(reference)
        val schemaBefore = schema(referenceUrl)
        val rowsBefore = allRows(referenceUrl)
        assertEquals(emptySet(), danglingReferences(referenceUrl))

        runMigration(referenceUrl)

        assertEquals(schemaBefore, schema(referenceUrl))
        assertEquals(rowsBefore, allRows(referenceUrl))
    }

    @Test
    fun withEnforcedForeignKeysNoRowIsLostAndEnforcementStaysOn() {
        migrate(target = "1.11")
        fill(database)
        val before = allRows(url)

        val enforcedAfterwards = runMigration(url, enforceForeignKeys = true)

        assertTrue(enforcedAfterwards)
        assertEquals(emptySet(), danglingReferences(url))
        assertEquals(before, allRows(url))
    }

    @Test
    fun aConnectionInsideATransactionIsHandedBackInTheSameMode() {
        migrate(target = "1.11")
        fill(database)
        val before = allRows(url)

        val autoCommitAfterwards = DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_12__RepairForeignKeyTargets().migrate(context)
            connection.autoCommit
        }

        assertEquals(false, autoCommitAfterwards)
        assertEquals(emptySet(), danglingReferences(url))
        assertEquals(before, allRows(url))
    }
}
