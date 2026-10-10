package dev.dertyp.synara.db.migration

import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.DownloadedSongs
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object SongsBeforeUuidColumn : UUIDTable("song") {
    val title = text("title").default("")
    val duration = long("duration").default(0L)
    val musicBrainzId = text("musicBrainzId").nullable()
}

private object AlbumsBeforeUuidColumn : UUIDTable("album") {
    val name = text("name")
    val musicBrainzId = text("musicBrainzId").nullable()
}

class V1_3__MusicBrainzUUIDTest {
    private lateinit var file: Path
    private lateinit var url: String
    private lateinit var database: Database

    private val validUuid = UUID.randomUUID()
    private val keptBlob = ByteArray(16) { (it * 7 + 1).toByte() }

    private data class StoredRow(val title: String, val duration: Long, val musicBrainzId: ByteArray?)

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration-uuid", ".db")
        url = "jdbc:sqlite:$file"
        database = Database.connect(url, driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun insertSong(title: String, duration: Long, musicBrainzId: Any?) {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("INSERT INTO song (id, title, duration, musicBrainzId) VALUES (?, ?, ?, ?)").use {
                it.setBytes(1, uuidBytes(randomPlatformUUID()))
                it.setString(2, title)
                it.setLong(3, duration)
                when (musicBrainzId) {
                    is ByteArray -> it.setBytes(4, musicBrainzId)
                    is String -> it.setString(4, musicBrainzId)
                    else -> it.setNull(4, Types.NULL)
                }
                it.executeUpdate()
            }
        }
    }

    private fun insertAlbum(name: String, musicBrainzId: String?) {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("INSERT INTO album (id, name, musicBrainzId) VALUES (?, ?, ?)").use {
                it.setBytes(1, uuidBytes(randomPlatformUUID()))
                it.setString(2, name)
                if (musicBrainzId == null) it.setNull(3, Types.NULL) else it.setString(3, musicBrainzId)
                it.executeUpdate()
            }
        }
    }

    private fun createLegacySongs() {
        transaction(database) { SchemaUtils.create(SongsBeforeUuidColumn) }
        insertSong("valid", 111, validUuid.toString())
        insertSong("invalid", 222, "not-a-uuid")
        insertSong("missing", 333, null)
        insertSong("blob", 444, keptBlob)
    }

    private fun storedSongs(): Map<String, StoredRow> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title, duration, musicBrainzId FROM song").use { rs ->
                buildMap {
                    while (rs.next()) {
                        val title = rs.getString("title")
                        put(title, StoredRow(title, rs.getLong("duration"), rs.getBytes("musicBrainzId")))
                    }
                }
            }
        }
    }

    private fun tableNames(): Set<String> = DriverManager.getConnection(url).use { connection ->
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("TABLE_NAME")) }
        }
    }

    private fun runMigration() {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_3__MusicBrainzUUID().migrate(context)
            connection.commit()
        }
    }

    @Test
    fun validUuidStringBecomesTheSixteenByteBlob() {
        createLegacySongs()

        runMigration()

        assertContentEquals(uuidBytes(validUuid), storedSongs().getValue("valid").musicBrainzId)
    }

    @Test
    fun migratedUuidIsReadBackAsTheSameUuid() {
        createLegacySongs()

        runMigration()

        val read = transaction(database) {
            DownloadedSongs.select(DownloadedSongs.musicBrainzId)
                .where { DownloadedSongs.title eq "valid" }
                .single()[DownloadedSongs.musicBrainzId]
        }
        assertEquals(validUuid, read)
    }

    @Test
    fun invalidStringAndMissingValueBecomeNull() {
        createLegacySongs()

        runMigration()

        val stored = storedSongs()
        assertNull(stored.getValue("invalid").musicBrainzId)
        assertNull(stored.getValue("missing").musicBrainzId)
    }

    @Test
    fun existingBlobIsKeptUnchanged() {
        createLegacySongs()

        runMigration()

        assertContentEquals(keptBlob, storedSongs().getValue("blob").musicBrainzId)
    }

    @Test
    fun rowCountAndOtherColumnsArePreserved() {
        createLegacySongs()

        runMigration()

        val stored = storedSongs()
        assertEquals(setOf("valid", "invalid", "missing", "blob"), stored.keys)
        assertEquals(111L, stored.getValue("valid").duration)
        assertEquals(222L, stored.getValue("invalid").duration)
        assertEquals(333L, stored.getValue("missing").duration)
        assertEquals(444L, stored.getValue("blob").duration)
    }

    @Test
    fun noBackupTableIsLeftBehind() {
        createLegacySongs()

        runMigration()

        assertTrue(tableNames().none { it.endsWith("_old") })
        assertTrue("song" in tableNames())
    }

    @Test
    fun rebuiltTableMatchesTheCurrentSchema() {
        createLegacySongs()

        runMigration()

        val columns = DriverManager.getConnection(url).use { connection ->
            connection.metaData.getColumns(null, null, "song", null).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("COLUMN_NAME")) }
            }
        }
        assertEquals(DownloadedSongs.columns.map { it.name }.toSet(), columns)
    }

    @Test
    fun albumTableIsConvertedToo() {
        transaction(database) { SchemaUtils.create(AlbumsBeforeUuidColumn) }
        val albumUuid = UUID.randomUUID()
        insertAlbum("valid", albumUuid.toString())
        insertAlbum("invalid", "garbage")

        runMigration()

        val stored = DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name, musicBrainzId FROM album").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString("name"), rs.getBytes("musicBrainzId")) }
                }
            }
        }
        assertContentEquals(uuidBytes(albumUuid), stored.getValue("valid"))
        assertNull(stored.getValue("invalid"))
    }

    @Test
    fun missingTablesAreSkipped() {
        runMigration()

        assertTrue(tableNames().none { it == "song" || it == "album" || it == "artist" || it == "userPlaylistSong" })
    }

    @Test
    fun emptySongTableIsRebuiltWithoutRows() {
        transaction(database) { SchemaUtils.create(SongsBeforeUuidColumn) }

        runMigration()

        assertTrue(storedSongs().isEmpty())
    }
}
