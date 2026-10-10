package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.randomPlatformUUID
import kotlinx.coroutines.runBlocking
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

class ExposedUserRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedUserRepository

    private val firstImageId = randomPlatformUUID()
    private val secondImageId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-user", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(
                DownloadedImages,
                DownloadedUsers,
                DownloadedUserPlaylists,
                RecentlyPlayedSongs,
                RecentlyPlayedAlbums,
                RecentlyPlayedArtists,
                ScrobbleQueue,
                LocalHistory,
                SongGuessGames,
                SongGuessSavedGames
            )
            listOf(firstImageId, secondImageId).forEach { imageId ->
                DownloadedImages.insert {
                    it[id] = imageId
                    it[path] = "/$imageId.jpg"
                    it[imageHash] = imageId.toString()
                    it[origin] = "test"
                }
            }
        }
        repository = ExposedUserRepository()
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun userCount(): Long = transaction(database) { DownloadedUsers.selectAll().count() }

    private fun usernames(): List<Pair<PlatformUUID, String>> = transaction(database) {
        DownloadedUsers.selectAll().map { it[DownloadedUsers.id].value to it[DownloadedUsers.username] }
    }

    @Test
    fun savedUserIsReturnedWithAllStoredFields() = runBlocking {
        val user = User(
            id = randomPlatformUUID(),
            username = "mac",
            displayName = "Mac Miller",
            isAdmin = true,
            profileImageId = firstImageId
        )

        repository.saveUser(user)

        val loaded = repository.getUser(user.id)!!
        assertEquals(user.id, loaded.id)
        assertEquals("mac", loaded.username)
        assertEquals("Mac Miller", loaded.displayName)
        assertEquals(true, loaded.isAdmin)
        assertEquals(firstImageId, loaded.profileImageId)
    }

    @Test
    fun userWithoutOptionalFieldsRoundTripsAsNull() = runBlocking {
        val user = User(id = randomPlatformUUID(), username = "plain")

        repository.saveUser(user)

        val loaded = repository.getUser(user.id)!!
        assertNull(loaded.displayName)
        assertNull(loaded.profileImageId)
        assertEquals(false, loaded.isAdmin)
    }

    @Test
    fun unknownIdReturnsNull() = runBlocking {
        repository.saveUser(User(id = randomPlatformUUID(), username = "someone"))

        assertNull(repository.getUser(randomPlatformUUID()))
    }

    @Test
    fun savingTheSameIdTwiceKeepsOneRowWithTheNewValues() = runBlocking {
        val id = randomPlatformUUID()
        repository.saveUser(User(id = id, username = "mac", displayName = "Old", isAdmin = false, profileImageId = firstImageId))

        repository.saveUser(User(id = id, username = "mac", displayName = "New", isAdmin = true, profileImageId = secondImageId))

        assertEquals(1, userCount())
        val loaded = repository.getUser(id)!!
        assertEquals("New", loaded.displayName)
        assertEquals(true, loaded.isAdmin)
        assertEquals(secondImageId, loaded.profileImageId)
    }

    @Test
    fun savingTheSameIdWithoutOptionalFieldsClearsThem() = runBlocking {
        val id = randomPlatformUUID()
        repository.saveUser(User(id = id, username = "mac", displayName = "Old", profileImageId = firstImageId))

        repository.saveUser(User(id = id, username = "mac"))

        val loaded = repository.getUser(id)!!
        assertNull(loaded.displayName)
        assertNull(loaded.profileImageId)
    }

    @Test
    fun savingTheSameIdWithANewUsernameRenamesInPlace() = runBlocking {
        val id = randomPlatformUUID()
        repository.saveUser(User(id = id, username = "before"))

        repository.saveUser(User(id = id, username = "after"))

        assertEquals(1, userCount())
        assertEquals("after", repository.getUser(id)!!.username)
    }

    @Test
    fun differentIdsAreStoredAsSeparateRows() = runBlocking {
        val first = User(id = randomPlatformUUID(), username = "first")
        val second = User(id = randomPlatformUUID(), username = "second")

        repository.saveUser(first)
        repository.saveUser(second)

        assertEquals(2, userCount())
        assertEquals("first", repository.getUser(first.id)!!.username)
        assertEquals("second", repository.getUser(second.id)!!.username)
    }

    @Test
    fun savingAUserWhoseUsernameBelongsToAnotherIdLeavesOneRowForTheNewId() = runBlocking {
        val staleId = randomPlatformUUID()
        val currentId = randomPlatformUUID()
        repository.saveUser(User(id = staleId, username = "mac"))

        repository.saveUser(User(id = currentId, username = "mac"))

        assertEquals(listOf(currentId to "mac"), usernames())
        assertEquals(currentId, repository.getUser(currentId)!!.id)
        assertNull(repository.getUser(staleId))
    }
}
