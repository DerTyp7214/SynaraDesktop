package dev.dertyp.synara.db.migration

import dev.dertyp.core.splitTitleTags
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object SongsBeforeTitleTags : UUIDTable("song") {
    val title = text("title").nullable()
    val duration = long("duration").default(0L)
}

class V1_7__SongTitleTagsTest {
    private lateinit var file: Path
    private lateinit var url: String
    private lateinit var database: Database

    private val featuring = "Song (feat. X)"
    private val plain = "Plain"
    private val remix = "Song (Remix)"

    private data class StoredSong(val title: String?, val tags: String)

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration-song-tags", ".db")
        url = "jdbc:sqlite:$file"
        database = Database.connect(url, driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun createLegacySongs(vararg titles: String?) {
        transaction(database) {
            SchemaUtils.create(SongsBeforeTitleTags)
            titles.forEachIndexed { index, storedTitle ->
                SongsBeforeTitleTags.insert {
                    it[title] = storedTitle
                    it[duration] = 1000L + index
                }
            }
        }
    }

    private fun columns(): Set<String> = DriverManager.getConnection(url).use { connection ->
        connection.metaData.getColumns(null, null, "song", null).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("COLUMN_NAME")) }
        }
    }

    private fun storedSongs(): List<StoredSong> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title, tags FROM song ORDER BY duration").use { rs ->
                buildList { while (rs.next()) add(StoredSong(rs.getString("title"), rs.getString("tags"))) }
            }
        }
    }

    private fun decodedTags(raw: String): List<TitleTag> = Json.decodeFromString(raw)

    private fun runMigration() {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_7__SongTitleTags().migrate(context)
            connection.commit()
        }
    }

    @Test
    fun addsTheTagsColumn() {
        createLegacySongs(plain)
        assertTrue("tags" !in columns())

        runMigration()

        assertTrue("tags" in columns())
    }

    @Test
    fun titlesAndTagsMatchSplitTitleTags() {
        val originals = listOf(featuring, plain, remix)
        createLegacySongs(*originals.toTypedArray())

        runMigration()

        val stored = storedSongs()
        assertEquals(originals.size, stored.size)
        originals.zip(stored).forEach { (original, row) ->
            val expected = original.splitTitleTags()
            assertEquals(expected.title, row.title)
            assertEquals(expected.tags, decodedTags(row.tags))
        }
    }

    @Test
    fun bracketedMarkersMoveFromTheTitleIntoTags() {
        createLegacySongs(featuring, remix)

        runMigration()

        val stored = storedSongs()
        assertEquals("Song", stored[0].title)
        assertEquals(listOf(TitleTagKind.FEAT), decodedTags(stored[0].tags).map { it.kind })
        assertEquals("Song", stored[1].title)
        assertEquals(listOf(TitleTagKind.REMIX), decodedTags(stored[1].tags).map { it.kind })
    }

    @Test
    fun plainTitleIsLeftUntouched() {
        createLegacySongs(featuring, plain, remix)

        runMigration()

        val row = storedSongs()[1]
        assertEquals(plain, row.title)
        assertEquals("[]", row.tags)
    }

    @Test
    fun runningTwiceChangesNothing() {
        createLegacySongs(featuring, plain, remix)
        runMigration()
        val once = storedSongs()

        runMigration()

        assertEquals(once, storedSongs())
    }

    @Test
    fun nullTitleIsSkipped() {
        createLegacySongs(null, featuring)

        runMigration()

        val stored = storedSongs()
        assertNull(stored[0].title)
        assertEquals("[]", stored[0].tags)
        assertEquals("Song", stored[1].title)
    }

    @Test
    fun otherColumnsSurvive() {
        createLegacySongs(featuring, plain)

        runMigration()

        val durations = DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT duration FROM song ORDER BY duration").use { rs ->
                    buildList { while (rs.next()) add(rs.getLong("duration")) }
                }
            }
        }
        assertEquals(listOf(1000L, 1001L), durations)
    }

    @Test
    fun schemaThatAlreadyHasTheColumnIsFine() {
        transaction(database) {
            SchemaUtils.create(SongsBeforeTitleTags)
            SongsBeforeTitleTags.insert {
                it[title] = featuring
                it[duration] = 1000L
            }
        }
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE \"song\" ADD COLUMN \"tags\" TEXT NOT NULL DEFAULT '[]'")
            }
        }

        runMigration()

        val row = storedSongs().single()
        assertEquals("Song", row.title)
        assertEquals(listOf(TitleTag(TitleTagKind.FEAT, "feat. X")), decodedTags(row.tags))
    }

    @Test
    fun emptyTableIsFine() {
        createLegacySongs()

        runMigration()

        assertTrue("tags" in columns())
        assertTrue(storedSongs().isEmpty())
    }
}
