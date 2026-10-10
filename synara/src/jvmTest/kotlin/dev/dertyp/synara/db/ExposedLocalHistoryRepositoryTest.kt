package dev.dertyp.synara.db

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
import kotlin.test.assertTrue

class ExposedLocalHistoryRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedLocalHistoryRepository

    private val userId = randomPlatformUUID()
    private val otherUserId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-local-history", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(DownloadedImages, DownloadedUsers, LocalHistory)
            listOf(userId, otherUserId).forEach { user ->
                DownloadedUsers.insert {
                    it[id] = user
                    it[username] = user.toString()
                    it[passwordHash] = ""
                }
            }
        }
        repository = ExposedLocalHistoryRepository(AppJson)
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun song(index: Int): UserSong =
        testSong(index, album = testAlbum(index), artists = listOf(testArtist(index)))

    private fun rowCount(): Long = transaction(database) {
        LocalHistory.selectAll().where { LocalHistory.userId eq userId }.count()
    }

    @Test
    fun getReturnsTheNewestEntriesNewestFirst() = runBlocking {
        val songs = (1..5).map { song(it) }
        songs.forEachIndexed { index, song -> repository.insert(userId, song, (index + 1).toLong()) }

        val entries = repository.get(userId, 3)

        assertEquals(listOf(5L, 4L, 3L), entries.map { it.timestamp })
        assertEquals(listOf(songs[4].id, songs[3].id, songs[2].id), entries.map { it.songId })
    }

    @Test
    fun insertionOrderDoesNotDecideTheOrder() = runBlocking {
        val newest = song(1)
        val oldest = song(2)
        val middle = song(3)
        repository.insert(userId, newest, 300)
        repository.insert(userId, oldest, 100)
        repository.insert(userId, middle, 200)

        assertEquals(listOf(newest.id, middle.id, oldest.id), repository.get(userId, 10).map { it.songId })
    }

    @Test
    fun limitAboveTheCountReturnsEverything() = runBlocking {
        (1..5).forEach { repository.insert(userId, song(it), it.toLong()) }

        assertEquals(5, repository.get(userId, 100).size)
    }

    @Test
    fun userWithoutHistoryGetsAnEmptyList() = runBlocking {
        assertTrue(repository.get(userId, 10).isEmpty())
    }

    @Test
    fun historyIsIsolatedPerUser() = runBlocking {
        val own = song(1)
        val foreign = song(2)
        repository.insert(userId, own, 1)
        repository.insert(otherUserId, foreign, 2)

        assertEquals(listOf(own.id), repository.get(userId, 10).map { it.songId })
        assertEquals(listOf(foreign.id), repository.get(otherUserId, 10).map { it.songId })
    }

    @Test
    fun songIdMatchesTheSongAndThePayloadDecodes() = runBlocking {
        val song = song(1)
        repository.insert(userId, song, 99)

        val entry = repository.get(userId, 1).single()

        assertEquals(song.id, entry.songId)
        assertEquals(song, entry.song)
        assertEquals(userId, entry.userId)
        assertEquals(99L, entry.timestamp)
    }

    @Test
    fun listeningToTheSameSongTwiceStoresTwoRows() = runBlocking {
        val song = song(1)
        repository.insert(userId, song, 1)
        repository.insert(userId, song, 2)

        val entries = repository.get(userId, 10)

        assertEquals(2L, rowCount())
        assertEquals(listOf(song.id, song.id), entries.map { it.songId })
        assertEquals(2, entries.map { it.id }.toSet().size)
    }
}
