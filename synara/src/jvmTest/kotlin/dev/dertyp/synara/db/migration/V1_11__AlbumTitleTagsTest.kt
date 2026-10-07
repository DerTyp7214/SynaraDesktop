package dev.dertyp.synara.db.migration

import dev.dertyp.PlatformUUID
import dev.dertyp.core.fullName
import dev.dertyp.data.Album
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.DownloadedAlbumArtists
import dev.dertyp.synara.db.DownloadedAlbumGenres
import dev.dertyp.synara.db.DownloadedAlbums
import dev.dertyp.synara.db.DownloadedArtistGenres
import dev.dertyp.synara.db.DownloadedArtistMembers
import dev.dertyp.synara.db.DownloadedArtists
import dev.dertyp.synara.db.DownloadedGenres
import dev.dertyp.synara.db.DownloadedImages
import dev.dertyp.synara.db.ExposedLibraryRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
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
import kotlin.test.assertTrue

private object AlbumsBeforeTags : UUIDTable("album") {
    val name = text("name")
    val releaseDate = varchar("releaseDate", 128).nullable()
    val songCount = integer("songCount").default(0)
    val cover = reference("cover", DownloadedImages.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val originalId = text("originalId").nullable()
    val totalDuration = long("totalDuration").default(0L)
    val totalSize = long("totalSize").default(0L)
    val explicitlySaved = bool("explicitlySaved").default(false)
    val musicBrainzId = javaUUID("musicBrainzId").nullable()
}

class V1_11__AlbumTitleTagsTest {
    private lateinit var file: Path
    private lateinit var url: String
    private lateinit var database: Database
    private val repository = ExposedLibraryRepository()

    private val anniversaryId = randomPlatformUUID()
    private val remasterId = randomPlatformUUID()
    private val stackedId = randomPlatformUUID()
    private val plainId = randomPlatformUUID()
    private val unrelatedBracketId = randomPlatformUUID()

    private val storedNames = mapOf(
        anniversaryId to "The Divine Feminine (10th Anniversary)",
        remasterId to "Abbey Road (2019 Remaster)",
        stackedId to "Rumours (Deluxe Edition) (2004 Remaster)",
        plainId to "Swimming",
        unrelatedBracketId to "Live (At the Royal Albert Hall)"
    )

    private val relatedTables: Array<Table> = arrayOf(
        DownloadedArtists,
        DownloadedGenres,
        DownloadedAlbumArtists,
        DownloadedArtistMembers,
        DownloadedAlbumGenres,
        DownloadedArtistGenres
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-migration", ".db")
        url = "jdbc:sqlite:$file"
        database = Database.connect(url, driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun createSchemaBeforeTags() {
        transaction(database) {
            SchemaUtils.create(DownloadedImages, AlbumsBeforeTags, *relatedTables)
            storedNames.forEach { (albumId, storedName) ->
                AlbumsBeforeTags.insert {
                    it[id] = albumId
                    it[name] = storedName
                    it[songCount] = 12
                    it[explicitlySaved] = true
                }
            }
        }
    }

    private fun columns(): Set<String> = DriverManager.getConnection(url).use { connection ->
        val names = mutableSetOf<String>()
        connection.metaData.getColumns(null, null, DownloadedAlbums.tableName, null).use { rs ->
            while (rs.next()) names += rs.getString("COLUMN_NAME")
        }
        names
    }

    private fun runMigration() {
        DriverManager.getConnection(url).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_11__AlbumTitleTags().migrate(context)
            connection.commit()
        }
    }

    private fun album(id: PlatformUUID): Album = runBlocking { repository.getAlbum(id) }!!

    @Test
    fun storedNamesAreSplitIntoNameAndTags() {
        createSchemaBeforeTags()
        assertTrue("tags" !in columns())

        runMigration()

        assertTrue("tags" in columns())

        assertEquals("The Divine Feminine", album(anniversaryId).name)
        assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")), album(anniversaryId).tags)

        assertEquals("Abbey Road", album(remasterId).name)
        assertEquals(listOf(TitleTag(TitleTagKind.REMASTER, "2019 Remaster")), album(remasterId).tags)

        assertEquals("Rumours", album(stackedId).name)
        assertEquals(
            listOf(TitleTag(TitleTagKind.VERSION, "Deluxe Edition"), TitleTag(TitleTagKind.REMASTER, "2004 Remaster")),
            album(stackedId).tags
        )

        assertEquals("Swimming", album(plainId).name)
        assertEquals(emptyList(), album(plainId).tags)

        assertEquals("Live (At the Royal Albert Hall)", album(unrelatedBracketId).name)
        assertEquals(emptyList(), album(unrelatedBracketId).tags)
    }

    @Test
    fun fullNameStaysTheStoredName() {
        createSchemaBeforeTags()
        runMigration()

        storedNames.forEach { (id, storedName) -> assertEquals(storedName, album(id).fullName) }
    }

    @Test
    fun otherColumnsSurvive() {
        createSchemaBeforeTags()
        runMigration()

        storedNames.keys.forEach { id ->
            assertEquals(12, album(id).songCount)
            assertTrue(runBlocking { repository.isAlbumSaved(id, explicitlySavedOnly = true) })
        }
    }

    @Test
    fun runningTwiceChangesNothing() {
        createSchemaBeforeTags()
        runMigration()
        val once = storedNames.keys.map { album(it) }

        runMigration()

        assertEquals(once, storedNames.keys.map { album(it) })
    }

    @Test
    fun schemaThatAlreadyHasTheColumnIsFine() {
        transaction(database) {
            SchemaUtils.create(DownloadedImages, DownloadedAlbums, *relatedTables)
            DownloadedAlbums.insert {
                it[id] = anniversaryId
                it[name] = "The Divine Feminine (10th Anniversary)"
            }
        }

        runMigration()

        assertEquals("The Divine Feminine", album(anniversaryId).name)
        assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")), album(anniversaryId).tags)
    }

    @Test
    fun emptyTableIsFine() {
        transaction(database) { SchemaUtils.create(DownloadedImages, AlbumsBeforeTags, *relatedTables) }

        runMigration()

        assertTrue("tags" in columns())
    }
}
