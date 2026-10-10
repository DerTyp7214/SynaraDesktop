package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
import dev.dertyp.synara.testing.testSong
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExposedScrobbleQueueRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedScrobbleQueueRepository

    private val userId = randomPlatformUUID()
    private val otherUserId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-scrobble-queue", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(DownloadedImages, DownloadedUsers, ScrobbleQueue)
            listOf(userId, otherUserId).forEach { user ->
                DownloadedUsers.insert {
                    it[id] = user
                    it[username] = user.toString()
                    it[passwordHash] = ""
                }
            }
        }
        repository = ExposedScrobbleQueueRepository(AppJson)
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun song(index: Int): UserSong =
        testSong(index, album = testAlbum(index), artists = listOf(testArtist(index)))

    private fun storedRowCount(): Long = transaction(database) { ScrobbleQueue.selectAll().count() }

    private fun storedIdOf(songId: PlatformUUID): Long = transaction(database) {
        ScrobbleQueue.selectAll().where { ScrobbleQueue.songId eq songId.toString() }
            .single()[ScrobbleQueue.id].value.toLong()
    }

    @Test
    fun entriesComeBackOrderedByTimestampAndPeekReturnsTheOldest() = runBlocking {
        val thirty = song(30)
        val ten = song(10)
        val twenty = song(20)
        repository.insert(userId, thirty, 30, "server")
        repository.insert(userId, ten, 10, "server")
        repository.insert(userId, twenty, 20, "server")

        val entries = repository.getAll(userId, "server")

        assertEquals(listOf(10L, 20L, 30L), entries.map { it.timestamp })
        assertEquals(listOf(ten.id, twenty.id, thirty.id), entries.map { it.song.id })
        val peeked = repository.peek(userId, "server")!!
        assertEquals(10L, peeked.timestamp)
        assertEquals(ten.id, peeked.song.id)
    }

    @Test
    fun rawEntriesComeBackOrderedByTimestamp() = runBlocking {
        repository.insertRaw(userId, "c", "payload-c", 30, "server")
        repository.insertRaw(userId, "a", "payload-a", 10, "server")
        repository.insertRaw(userId, "b", "payload-b", 20, "server")

        assertEquals(
            listOf("payload-a", "payload-b", "payload-c"),
            repository.getAllRaw(userId, "server").map { it.payload }
        )
    }

    @Test
    fun usersAndTargetsAreIsolatedFromEachOther() = runBlocking {
        val songs = (0 until 4).map { song(it) }
        repository.insert(userId, songs[0], 1, "server")
        repository.insert(otherUserId, songs[1], 2, "server")
        repository.insert(userId, songs[2], 3, "lastfm")
        repository.insert(userId, songs[3], 4, "lastfm")
        repository.insertRaw(otherUserId, "raw", "raw-payload", 5, "lastfm")

        assertEquals(listOf(songs[0].id), repository.getAll(userId, "server").map { it.song.id })
        assertEquals(listOf(songs[1].id), repository.getAll(otherUserId, "server").map { it.song.id })
        assertEquals(listOf(songs[2].id, songs[3].id), repository.getAll(userId, "lastfm").map { it.song.id })
        assertEquals(listOf(1L, 1L, 2L, 1L), listOf(
            repository.getCount(userId, "server"),
            repository.getCount(otherUserId, "server"),
            repository.getCount(userId, "lastfm"),
            repository.getCount(otherUserId, "lastfm")
        ))
        assertEquals(listOf(1L), repository.getAllRaw(userId, "server").map { it.timestamp })
        assertEquals(listOf(2L), repository.getAllRaw(otherUserId, "server").map { it.timestamp })
        assertEquals(listOf(3L, 4L), repository.getAllRaw(userId, "lastfm").map { it.timestamp })
        assertEquals(listOf("raw-payload"), repository.getAllRaw(otherUserId, "lastfm").map { it.payload })
    }

    @Test
    fun insertedSongRoundTripsAndEntryIdIsTheGeneratedId() = runBlocking {
        val song = song(1)
        repository.insert(userId, song, 42, "server")

        val entry = repository.getAll(userId, "server").single()

        assertEquals(song, entry.song)
        assertEquals(userId, entry.userId)
        assertEquals(42L, entry.timestamp)
        assertEquals("server", entry.target)
        assertEquals(storedIdOf(song.id), entry.id)
        assertEquals(entry.id, repository.peek(userId, "server")!!.id)
    }

    @Test
    fun insertedEntriesGetDistinctIds() = runBlocking {
        repository.insert(userId, song(1), 1, "server")
        repository.insert(userId, song(2), 2, "server")

        val ids = repository.getAll(userId, "server").map { it.id }

        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun rawPayloadIsReturnedVerbatimAndItsIdDeletesIt() = runBlocking {
        val payload = """{"songId":"x","note":"  keep   spacing "}"""
        repository.insertRaw(userId, "x", payload, 7, "server")

        val entry = repository.getAllRaw(userId, "server").single()

        assertEquals(payload, entry.payload)
        assertEquals(userId, entry.userId)
        assertEquals(7L, entry.timestamp)
        assertEquals("server", entry.target)

        repository.delete(entry.id)

        assertEquals(emptyList(), repository.getAllRaw(userId, "server"))
    }

    @Test
    fun deletingTheMiddleEntryLeavesTheOthers() = runBlocking {
        repository.insertRaw(userId, "a", "first", 1, "server")
        repository.insertRaw(userId, "b", "second", 2, "server")
        repository.insertRaw(userId, "c", "third", 3, "server")
        val middle = repository.getAllRaw(userId, "server").first { it.payload == "second" }

        repository.delete(middle.id)

        assertEquals(listOf("first", "third"), repository.getAllRaw(userId, "server").map { it.payload })
        assertEquals(2L, repository.getCount(userId, "server"))
    }

    @Test
    fun deletingAnUnknownIdChangesNothing() = runBlocking {
        repository.insertRaw(userId, "a", "first", 1, "server")
        val existing = repository.getAllRaw(userId, "server").single()

        repository.delete(existing.id + 1000)

        assertEquals(1L, storedRowCount())
        assertEquals(listOf(existing), repository.getAllRaw(userId, "server"))
    }

    @Test
    fun emptyQueueHasNoPeekAndCountsZero() = runBlocking {
        assertNull(repository.peek(userId, "server"))
        assertEquals(0L, repository.getCount(userId, "server"))
        assertTrue(repository.getAll(userId, "server").isEmpty())
        assertTrue(repository.getAllRaw(userId, "server").isEmpty())
    }

    @Test
    fun corruptTypedPayloadIsStillReturnedByRawListing() = runBlocking {
        repository.insertRaw(userId, "broken", "this is not a song", 5, "server")

        val raw = repository.getAllRaw(userId, "server").single()

        assertEquals("this is not a song", raw.payload)
        assertEquals(1L, repository.getCount(userId, "server"))
    }
}
