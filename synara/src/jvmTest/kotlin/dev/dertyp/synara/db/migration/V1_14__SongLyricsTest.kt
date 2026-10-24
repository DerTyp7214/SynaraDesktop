package dev.dertyp.synara.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.PlatformUUID
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.models.LyricLine
import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.services.models.SyncedLyrics
import dev.dertyp.synara.db.*
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.Context
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class V1_14__SongLyricsTest {
    private lateinit var file: Path
    private lateinit var referenceFile: Path
    private lateinit var url: String
    private lateinit var referenceUrl: String
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var reference: Database

    private val song = DownloadedSongs.tableName
    private val lyricsTable = DownloadedSongLyrics.tableName
    private val store = ExposedOfflineLyricsStore()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration-song-lyrics", ".db")
        referenceFile = Files.createTempFile("synara-migration-song-lyrics-reference", ".db")
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
        transaction(reference) { SchemaUtils.create(DownloadedImages, DownloadedAlbums, DownloadedSongs, DownloadedSongLyrics) }
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

    private fun rows(url: String, table: String, columns: List<String?> = columns(url, table).map { it[0] }): List<String> {
        val quoted = columns.joinToString(", ") { "quote(\"$it\")" }
        return query(url, "SELECT $quoted FROM \"$table\"").map { it.toString() }.sorted()
    }

    private fun danglingReferences(url: String): Set<Pair<String, String?>> {
        val tables = query(url, "SELECT name FROM sqlite_master WHERE type = 'table'").mapTo(HashSet()) { it[0] }
        return tables.flatMapTo(HashSet()) { table ->
            query(url, "PRAGMA foreign_key_list(\"$table\")").map { table!! to it[2] }
        }.filterTo(HashSet()) { (_, target) -> target !in tables }
    }

    private fun addLegacyLyricsColumn() {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.execute("ALTER TABLE \"$song\" ADD COLUMN \"lyrics\" TEXT DEFAULT '' NOT NULL") }
        }
    }

    private fun insertSong(title: String, lyrics: String): PlatformUUID {
        val id = randomPlatformUUID()
        transaction(database) {
            DownloadedSongs.insert {
                it[this.id] = id
                it[this.title] = title
                it[duration] = 200_000L
                it[filePath] = "/music/$title.flac"
            }
        }
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("UPDATE \"$song\" SET \"lyrics\" = ? WHERE \"title\" = ?").use { update ->
                update.setString(1, lyrics)
                update.setString(2, title)
                update.executeUpdate()
            }
        }
        return id
    }

    private fun legacyDatabase(): Map<String, PlatformUUID> {
        migrate(target = "1.13")
        addLegacyLyricsColumn()
        return mapOf(
            "plain" to insertSong("plain", "first line\n\nsecond line\n"),
            "lrc" to insertSong("lrc", "[ar:Mac Miller]\n[00:01.50] one\n[00:03.00]\n[00:04.25][00:09.5] two"),
            "empty" to insertSong("empty", ""),
            "blank" to insertSong("blank", "  \n ")
        )
    }

    @Test
    fun plainTextMovesAsPlainLyricsOfTheSong() {
        val ids = legacyDatabase()

        migrate()

        val moved = runBlocking { store.getLyrics(ids.getValue("plain")) }!!
        assertEquals("song", moved.provider.id)
        assertEquals(LyricsSyncLevel.PLAIN, moved.syncLevel)
        assertEquals(LyricsMatch.PROVIDER, moved.match)
        assertEquals("first line\n\nsecond line", moved.plain)
        assertNull(moved.lyrics)
        assertEquals(ids.getValue("plain"), moved.songId)
    }

    @Test
    fun lrcTextMovesAsLineSyncedLyrics() {
        val ids = legacyDatabase()

        migrate()

        val moved = runBlocking { store.getLyrics(ids.getValue("lrc")) }!!
        assertEquals(LyricsSyncLevel.LINE, moved.syncLevel)
        assertEquals("one\ntwo\ntwo", moved.plain)
        assertEquals(
            SyncedLyrics(
                listOf(
                    LyricLine(1_500.milliseconds, 4_250.milliseconds, emptyList(), "one"),
                    LyricLine(4_250.milliseconds, 9_500.milliseconds, emptyList(), "two"),
                    LyricLine(9_500.milliseconds, 9_500.milliseconds, emptyList(), "two")
                )
            ),
            moved.lyrics
        )
    }

    @Test
    fun songsWithoutTextGetNoLyrics() {
        val ids = legacyDatabase()

        migrate()

        assertNull(runBlocking { store.getLyrics(ids.getValue("empty")) })
        assertNull(runBlocking { store.getLyrics(ids.getValue("blank")) })
        assertEquals(2, query(url, "SELECT COUNT(*) FROM \"$lyricsTable\"").single().single()!!.toInt())
    }

    @Test
    fun theLyricsColumnIsDroppedAndEveryOtherSongValueStays() {
        legacyDatabase()
        val kept = columns(url, song).map { it[0] }.filterNot { it == "lyrics" }
        val before = rows(url, song, kept)

        migrate()

        assertEquals(columns(referenceUrl, song), columns(url, song))
        assertEquals(foreignKeys(referenceUrl, song), foreignKeys(url, song))
        assertEquals(before, rows(url, song))
        assertEquals(emptySet(), danglingReferences(url))
    }

    @Test
    fun theLyricsTableMatchesTheExposedDefinition() {
        legacyDatabase()

        migrate()

        assertEquals(columns(referenceUrl, lyricsTable), columns(url, lyricsTable))
        assertEquals(foreignKeys(referenceUrl, lyricsTable), foreignKeys(url, lyricsTable))
    }

    @Test
    fun aFreshDatabaseGetsAnEmptyLyricsTable() {
        migrate()

        assertEquals(columns(referenceUrl, lyricsTable), columns(url, lyricsTable))
        assertEquals(columns(referenceUrl, song), columns(url, song))
        assertEquals(emptyList(), rows(url, lyricsTable))
    }

    @Test
    fun deletingASongWithEnforcedForeignKeysDeletesItsLyrics() {
        val ids = legacyDatabase()
        migrate()

        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA foreign_keys = ON")
                it.execute("DELETE FROM \"$song\" WHERE \"title\" = 'lrc'")
            }
        }

        assertNull(runBlocking { store.getLyrics(ids.getValue("lrc")) })
        assertNotNull(runBlocking { store.getLyrics(ids.getValue("plain")) })
    }

    @Test
    fun foreignKeyEnforcementIsRestored() {
        legacyDatabase()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_14__SongLyrics().migrate(context)
            assertEquals("1", query(connection, "PRAGMA foreign_keys").single().single())
        }
    }
}
