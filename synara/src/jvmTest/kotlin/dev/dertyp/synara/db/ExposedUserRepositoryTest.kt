package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.testing.InMemorySettings
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
import kotlin.test.assertTrue

class ExposedUserRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedUserRepository

    private val settings = InMemorySettings()
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
        useServer("first.example")
        repository = ExposedUserRepository(settings)
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun useServer(host: String?, port: Int? = 8080, path: String? = null) {
        settings.put(SettingKey.Host, host)
        settings.put(SettingKey.Port, port)
        settings.put(SettingKey.RpcPath, path)
    }

    private fun servers(): Map<PlatformUUID, String?> = transaction(database) {
        DownloadedUsers.selectAll().associate { it[DownloadedUsers.id].value to it[DownloadedUsers.server] }
    }

    private fun insertUnattributed(id: PlatformUUID, name: String) = transaction(database) {
        DownloadedUsers.insert {
            it[DownloadedUsers.id] = id
            it[username] = name
            it[displayName] = "Unattributed"
            it[passwordHash] = ""
        }
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

    @Test
    fun aSavedUserCarriesTheServerItWasSavedFrom() = runBlocking {
        val id = randomPlatformUUID()
        useServer("Music.Example ", port = 8443, path = "/rpc/")

        repository.saveUser(User(id = id, username = "mac"))

        assertEquals(mapOf<PlatformUUID, String?>(id to "music.example:8443/rpc"), servers())
    }

    @Test
    fun aServerWithoutAPathIsRecordedAsHostAndPort() = runBlocking {
        val first = randomPlatformUUID()
        val second = randomPlatformUUID()
        useServer("music.example", port = 443)
        repository.saveUser(User(id = first, username = "first"))
        useServer("music.example", port = 443, path = "/")

        repository.saveUser(User(id = second, username = "second"))

        assertEquals(mapOf<PlatformUUID, String?>(first to "music.example:443", second to "music.example:443"), servers())
    }

    @Test
    fun theSameUsernameOnAnotherServerIsStoredAsASecondRow() = runBlocking {
        val firstId = randomPlatformUUID()
        val secondId = randomPlatformUUID()
        repository.saveUser(User(id = firstId, username = "mac", displayName = "First"))
        useServer("second.example")

        repository.saveUser(User(id = secondId, username = "mac", displayName = "Second"))

        assertEquals(setOf(firstId to "mac", secondId to "mac"), usernames().toSet())
        assertEquals(
            mapOf<PlatformUUID, String?>(firstId to "first.example:8080", secondId to "second.example:8080"),
            servers()
        )
        assertEquals("First", repository.getUser(firstId)!!.displayName)
        assertEquals("Second", repository.getUser(secondId)!!.displayName)
    }

    @Test
    fun aLoginOnEitherServerSavesAndLoadsItsOwnUser() = runBlocking {
        val firstId = randomPlatformUUID()
        val secondId = randomPlatformUUID()
        repository.saveUser(User(id = firstId, username = "mac", displayName = "First"))
        useServer("second.example")
        repository.saveUser(User(id = secondId, username = "mac", displayName = "Second"))

        useServer("first.example")
        repository.saveUser(User(id = firstId, username = "mac", displayName = "First again"))
        useServer("second.example")
        repository.saveUser(User(id = secondId, username = "mac", displayName = "Second again"))

        assertEquals(2, userCount())
        assertEquals("First again", repository.getUser(firstId)!!.displayName)
        assertEquals("Second again", repository.getUser(secondId)!!.displayName)
        assertEquals(
            mapOf<PlatformUUID, String?>(firstId to "first.example:8080", secondId to "second.example:8080"),
            servers()
        )
    }

    @Test
    fun theSamePortOnAnotherHostAndTheSameHostOnAnotherPortOrPathAreOtherServers() = runBlocking {
        val ids = List(4) { randomPlatformUUID() }
        repository.saveUser(User(id = ids[0], username = "mac"))
        useServer("second.example")
        repository.saveUser(User(id = ids[1], username = "mac"))
        useServer("first.example", port = 9090)
        repository.saveUser(User(id = ids[2], username = "mac"))
        useServer("first.example", path = "/other")

        repository.saveUser(User(id = ids[3], username = "mac"))

        assertEquals(ids.toSet(), usernames().map { it.first }.toSet())
    }

    @Test
    fun aRowOfAnUnknownServerWithTheSameIdIsAttributedToTheServerOfTheLogin() = runBlocking {
        val id = randomPlatformUUID()
        insertUnattributed(id, "mac")

        repository.saveUser(User(id = id, username = "mac", displayName = "Mac"))

        assertEquals(mapOf<PlatformUUID, String?>(id to "first.example:8080"), servers())
        assertEquals("Mac", repository.getUser(id)!!.displayName)
    }

    @Test
    fun aRowOfAnUnknownServerWithTheSameNameAndAnotherIdIsLeftAloneAndTheLoginSucceeds() = runBlocking {
        val unattributedId = randomPlatformUUID()
        val currentId = randomPlatformUUID()
        insertUnattributed(unattributedId, "mac")

        repository.saveUser(User(id = currentId, username = "mac", displayName = "Mac"))

        assertEquals(
            mapOf<PlatformUUID, String?>(unattributedId to null, currentId to "first.example:8080"),
            servers()
        )
        assertEquals("Unattributed", repository.getUser(unattributedId)!!.displayName)
        assertEquals("Mac", repository.getUser(currentId)!!.displayName)
    }

    @Test
    fun aLeftAloneRowIsAttributedOnceItsOwnIdLogsIn() = runBlocking {
        val unattributedId = randomPlatformUUID()
        val currentId = randomPlatformUUID()
        insertUnattributed(unattributedId, "mac")
        repository.saveUser(User(id = currentId, username = "mac"))
        useServer("second.example")

        repository.saveUser(User(id = unattributedId, username = "mac"))

        assertEquals(
            mapOf<PlatformUUID, String?>(unattributedId to "second.example:8080", currentId to "first.example:8080"),
            servers()
        )
    }

    @Test
    fun theSameIdOnAnotherServerKeepsOneRowAttributedToTheLastLogin() = runBlocking {
        val id = randomPlatformUUID()
        repository.saveUser(User(id = id, username = "mac"))
        useServer("second.example")

        repository.saveUser(User(id = id, username = "mac"))

        assertEquals(mapOf<PlatformUUID, String?>(id to "second.example:8080"), servers())
    }

    @Test
    fun withoutAConfiguredServerUsersAreSavedByIdAndNoneIsTakenOver() = runBlocking {
        val firstId = randomPlatformUUID()
        val secondId = randomPlatformUUID()
        useServer(null, port = null)
        repository.saveUser(User(id = firstId, username = "mac"))

        repository.saveUser(User(id = secondId, username = "mac"))

        assertEquals(mapOf<PlatformUUID, String?>(firstId to null, secondId to null), servers())
    }

    @Test
    fun aSaveWithoutAConfiguredServerKeepsTheRecordedServer() = runBlocking {
        val id = randomPlatformUUID()
        repository.saveUser(User(id = id, username = "mac", displayName = "Old"))
        useServer(null, port = null)

        repository.saveUser(User(id = id, username = "mac", displayName = "New"))

        assertEquals(mapOf<PlatformUUID, String?>(id to "first.example:8080"), servers())
        assertEquals("New", repository.getUser(id)!!.displayName)
    }

    @Test
    fun aRenameToTheNameOfAnotherServersUserKeepsBothRows() = runBlocking {
        val firstId = randomPlatformUUID()
        val secondId = randomPlatformUUID()
        repository.saveUser(User(id = firstId, username = "mac"))
        useServer("second.example")
        repository.saveUser(User(id = secondId, username = "before"))

        repository.saveUser(User(id = secondId, username = "mac"))

        assertEquals(setOf(firstId to "mac", secondId to "mac"), usernames().toSet())
    }

    @Test
    fun twoRowsOfOneServerCannotShareAUsername() {
        val id = randomPlatformUUID()
        runBlocking { repository.saveUser(User(id = id, username = "mac")) }

        val duplicate = runCatching {
            transaction(database) {
                DownloadedUsers.insert {
                    it[DownloadedUsers.id] = randomPlatformUUID()
                    it[username] = "mac"
                    it[passwordHash] = ""
                    it[server] = "first.example:8080"
                }
            }
        }

        assertTrue(duplicate.isFailure)
        assertEquals(listOf(id to "mac"), usernames())
    }
}
