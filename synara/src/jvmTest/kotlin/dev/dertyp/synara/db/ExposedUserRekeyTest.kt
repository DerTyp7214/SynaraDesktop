package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.testing.InMemorySettings
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExposedUserRekeyTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedUserRepository

    private val settings = InMemorySettings()
    private val staleId = randomPlatformUUID()
    private val currentId = randomPlatformUUID()
    private val otherId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-user-rekey", ".db")
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
        }
        useServer("first.example")
        repository = ExposedUserRepository(settings)
        runBlocking {
            repository.saveUser(User(id = staleId, username = "mac"))
            repository.saveUser(User(id = otherId, username = "other"))
        }
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun useServer(host: String) {
        settings.put(SettingKey.Host, host)
        settings.put(SettingKey.Port, 8080)
    }

    private fun forgetTheServerOf(user: PlatformUUID) = transaction(database) {
        DownloadedUsers.update({ DownloadedUsers.id eq user }) { it[server] = null }
    }

    private fun fillEveryTable(user: PlatformUUID, mark: String, at: Long) {
        transaction(database) {
            DownloadedUserPlaylists.insert {
                it[id] = randomPlatformUUID()
                it[name] = mark
                it[creator] = user
            }
            ScrobbleQueue.insert {
                it[userId] = user
                it[songId] = "song"
                it[timestamp] = at
                it[target] = "server"
                it[payload] = mark
            }
            LocalHistory.insert {
                it[userId] = user
                it[songId] = "song"
                it[timestamp] = at
                it[payload] = mark
            }
            SongGuessGames.insert {
                it[userId] = user
                it[playedAt] = at
                it[score] = 1
                it[maxScore] = 30
                it[rounds] = 5
                it[config] = "config"
                it[roundResults] = mark
            }
        }
        playedSong(user, "song", at, mark)
        playedAlbum(user, "album", at, mark)
        playedArtist(user, "artist", at, mark)
        savedGame(user, at, mark)
    }

    private fun owners(): Map<String, Set<List<Any>>> = transaction(database) {
        mapOf(
            "users" to DownloadedUsers.selectAll().mapTo(HashSet()) { listOf(it[DownloadedUsers.id].value, it[DownloadedUsers.username]) },
            "playlists" to DownloadedUserPlaylists.selectAll().mapTo(HashSet()) {
                listOf(it[DownloadedUserPlaylists.creator].value, it[DownloadedUserPlaylists.name])
            },
            "scrobbles" to ScrobbleQueue.selectAll().mapTo(HashSet()) { listOf(it[ScrobbleQueue.userId].value, it[ScrobbleQueue.payload]) },
            "history" to LocalHistory.selectAll().mapTo(HashSet()) { listOf(it[LocalHistory.userId].value, it[LocalHistory.payload]) },
            "games" to SongGuessGames.selectAll().mapTo(HashSet()) { listOf(it[SongGuessGames.userId].value, it[SongGuessGames.roundResults]) }
        )
    }

    private fun playedSong(user: PlatformUUID, song: String, at: Long, content: String) = transaction(database) {
        RecentlyPlayedSongs.insert {
            it[userId] = user
            it[songId] = song
            it[timestamp] = at
            it[payload] = content
        }
    }

    private fun playedAlbum(user: PlatformUUID, album: String, at: Long, content: String) = transaction(database) {
        RecentlyPlayedAlbums.insert {
            it[userId] = user
            it[albumId] = album
            it[timestamp] = at
            it[payload] = content
        }
    }

    private fun playedArtist(user: PlatformUUID, artist: String, at: Long, content: String) = transaction(database) {
        RecentlyPlayedArtists.insert {
            it[userId] = user
            it[artistId] = artist
            it[timestamp] = at
            it[payload] = content
        }
    }

    private fun savedGame(user: PlatformUUID, at: Long, content: String) = transaction(database) {
        SongGuessSavedGames.insert {
            it[userId] = user
            it[updatedAt] = at
            it[state] = content
        }
    }

    private fun songs(): Set<List<Any>> = transaction(database) {
        RecentlyPlayedSongs.selectAll().mapTo(HashSet()) {
            listOf(it[RecentlyPlayedSongs.userId].value, it[RecentlyPlayedSongs.songId], it[RecentlyPlayedSongs.timestamp], it[RecentlyPlayedSongs.payload])
        }
    }

    private fun albums(): Set<List<Any>> = transaction(database) {
        RecentlyPlayedAlbums.selectAll().mapTo(HashSet()) {
            listOf(it[RecentlyPlayedAlbums.userId].value, it[RecentlyPlayedAlbums.albumId], it[RecentlyPlayedAlbums.timestamp], it[RecentlyPlayedAlbums.payload])
        }
    }

    private fun artists(): Set<List<Any>> = transaction(database) {
        RecentlyPlayedArtists.selectAll().mapTo(HashSet()) {
            listOf(it[RecentlyPlayedArtists.userId].value, it[RecentlyPlayedArtists.artistId], it[RecentlyPlayedArtists.timestamp], it[RecentlyPlayedArtists.payload])
        }
    }

    private fun savedGames(): Set<List<Any>> = transaction(database) {
        SongGuessSavedGames.selectAll().mapTo(HashSet()) {
            listOf(it[SongGuessSavedGames.userId].value, it[SongGuessSavedGames.updatedAt], it[SongGuessSavedGames.state])
        }
    }

    private fun saveCurrent() = runBlocking { repository.saveUser(User(id = currentId, username = "mac")) }

    @Test
    fun theUserRowOfTheUsernameCarriesTheNewIdAndOtherUsersStay() {
        saveCurrent()

        val users = transaction(database) {
            DownloadedUsers.selectAll().associate { it[DownloadedUsers.username] to it[DownloadedUsers.id].value }
        }
        assertEquals(mapOf("mac" to currentId, "other" to otherId), users)
    }

    @Test
    fun playlistsHistoryScrobblesAndLeaderboardMoveToTheNewId() {
        val stalePlaylist = randomPlatformUUID()
        val otherPlaylist = randomPlatformUUID()
        transaction(database) {
            listOf(stalePlaylist to staleId, otherPlaylist to otherId).forEach { (playlist, owner) ->
                DownloadedUserPlaylists.insert {
                    it[id] = playlist
                    it[name] = "Playlist"
                    it[creator] = owner
                }
            }
            listOf(staleId, currentId, otherId).forEachIndexed { index, user ->
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
                SongGuessGames.insert {
                    it[userId] = user
                    it[playedAt] = index.toLong()
                    it[score] = index
                    it[maxScore] = 30
                    it[rounds] = 5
                    it[config] = "config-$index"
                    it[roundResults] = "results-$index"
                }
            }
        }

        saveCurrent()

        transaction(database) {
            assertEquals(
                mapOf(stalePlaylist to currentId, otherPlaylist to otherId),
                DownloadedUserPlaylists.selectAll().associate { it[DownloadedUserPlaylists.id].value to it[DownloadedUserPlaylists.creator].value }
            )
            assertEquals(
                mapOf("scrobble-0" to currentId, "scrobble-1" to currentId, "scrobble-2" to otherId),
                ScrobbleQueue.selectAll().associate { it[ScrobbleQueue.payload] to it[ScrobbleQueue.userId].value }
            )
            assertEquals(
                mapOf("history-0" to currentId, "history-1" to currentId, "history-2" to otherId),
                LocalHistory.selectAll().associate { it[LocalHistory.payload] to it[LocalHistory.userId].value }
            )
            assertEquals(
                mapOf("results-0" to currentId, "results-1" to currentId, "results-2" to otherId),
                SongGuessGames.selectAll().associate { it[SongGuessGames.roundResults] to it[SongGuessGames.userId].value }
            )
        }
    }

    @Test
    fun recentlyPlayedMovesToTheNewIdAndAnEntryUnderBothIdsKeepsTheLaterListen() {
        playedSong(staleId, "only-stale", 10, "a")
        playedSong(staleId, "stale-later", 30, "stale wins")
        playedSong(currentId, "stale-later", 20, "current loses")
        playedSong(staleId, "current-later", 20, "stale loses")
        playedSong(currentId, "current-later", 30, "current wins")
        playedSong(currentId, "only-current", 40, "b")
        playedSong(otherId, "only-stale", 50, "c")
        playedAlbum(staleId, "album", 10, "stale loses")
        playedAlbum(currentId, "album", 10, "current wins")
        playedAlbum(staleId, "stale-album", 5, "d")
        playedArtist(staleId, "artist", 11, "stale wins")
        playedArtist(currentId, "artist", 10, "current loses")
        playedArtist(otherId, "artist", 12, "e")

        saveCurrent()

        assertEquals(
            setOf<List<Any>>(
                listOf(currentId, "only-stale", 10L, "a"),
                listOf(currentId, "stale-later", 30L, "stale wins"),
                listOf(currentId, "current-later", 30L, "current wins"),
                listOf(currentId, "only-current", 40L, "b"),
                listOf(otherId, "only-stale", 50L, "c")
            ),
            songs()
        )
        assertEquals(
            setOf<List<Any>>(
                listOf(currentId, "album", 10L, "current wins"),
                listOf(currentId, "stale-album", 5L, "d")
            ),
            albums()
        )
        assertEquals(
            setOf<List<Any>>(
                listOf(currentId, "artist", 11L, "stale wins"),
                listOf(otherId, "artist", 12L, "e")
            ),
            artists()
        )
    }

    @Test
    fun theSavedGameOfTheStaleIdMovesToTheNewId() {
        savedGame(staleId, 10, "stale")
        savedGame(otherId, 20, "other")

        saveCurrent()

        assertEquals(setOf<List<Any>>(listOf(currentId, 10L, "stale"), listOf(otherId, 20L, "other")), savedGames())
    }

    @Test
    fun withASavedGameUnderBothIdsTheLaterOneIsKept() {
        savedGame(staleId, 30, "stale")
        savedGame(currentId, 20, "current")

        saveCurrent()

        assertEquals(setOf<List<Any>>(listOf(currentId, 30L, "stale")), savedGames())
    }

    @Test
    fun aLaterSavedGameUnderTheNewIdIsKept() {
        savedGame(staleId, 20, "stale")
        savedGame(currentId, 30, "current")

        saveCurrent()

        assertEquals(setOf<List<Any>>(listOf(currentId, 30L, "current")), savedGames())
    }

    @Test
    fun savingAgainChangesNothing() {
        playedSong(staleId, "song", 10, "a")
        savedGame(staleId, 10, "stale")
        saveCurrent()
        val songsAfterFirst = songs()
        val gamesAfterFirst = savedGames()

        saveCurrent()

        assertEquals(songsAfterFirst, songs())
        assertEquals(gamesAfterFirst, savedGames())
    }

    @Test
    fun theSameUsernameOnAnotherServerTakesOverNothingAndBothUsersExist() {
        fillEveryTable(staleId, "first", 10)
        fillEveryTable(currentId, "second", 20)
        val ownersBefore = owners()
        val songsBefore = songs()
        val albumsBefore = albums()
        val artistsBefore = artists()
        val gamesBefore = savedGames()
        useServer("second.example")

        saveCurrent()

        assertEquals(
            ownersBefore + ("users" to setOf<List<Any>>(listOf(staleId, "mac"), listOf(currentId, "mac"), listOf(otherId, "other"))),
            owners()
        )
        assertEquals(songsBefore, songs())
        assertEquals(albumsBefore, albums())
        assertEquals(artistsBefore, artists())
        assertEquals(gamesBefore, savedGames())
    }

    @Test
    fun switchingBackAndForthBetweenTwoServersNeverMovesARow() {
        fillEveryTable(staleId, "first", 10)
        fillEveryTable(currentId, "second", 20)
        useServer("second.example")
        saveCurrent()
        val ownersBefore = owners()
        val songsBefore = songs()
        val gamesBefore = savedGames()

        useServer("first.example")
        runBlocking { repository.saveUser(User(id = staleId, username = "mac")) }
        useServer("second.example")
        saveCurrent()

        assertEquals(ownersBefore, owners())
        assertEquals(songsBefore, songs())
        assertEquals(gamesBefore, savedGames())
    }

    @Test
    fun aStaleIdOfTheSameServerIsStillTakenOverWhileAnotherServerHoldsTheUsername() {
        val elsewhereId = randomPlatformUUID()
        useServer("second.example")
        runBlocking { repository.saveUser(User(id = elsewhereId, username = "mac")) }
        fillEveryTable(elsewhereId, "second", 20)
        fillEveryTable(staleId, "first", 10)
        useServer("first.example")

        saveCurrent()

        assertEquals(
            mapOf(
                "users" to setOf<List<Any>>(listOf(currentId, "mac"), listOf(elsewhereId, "mac"), listOf(otherId, "other")),
                "playlists" to setOf<List<Any>>(listOf(currentId, "first"), listOf(elsewhereId, "second")),
                "scrobbles" to setOf<List<Any>>(listOf(currentId, "first"), listOf(elsewhereId, "second")),
                "history" to setOf<List<Any>>(listOf(currentId, "first"), listOf(elsewhereId, "second")),
                "games" to setOf<List<Any>>(listOf(currentId, "first"), listOf(elsewhereId, "second"))
            ),
            owners()
        )
        assertEquals(
            setOf<List<Any>>(listOf(currentId, "song", 10L, "first"), listOf(elsewhereId, "song", 20L, "second")),
            songs()
        )
        assertEquals(setOf<List<Any>>(listOf(currentId, 10L, "first"), listOf(elsewhereId, 20L, "second")), savedGames())
    }

    @Test
    fun aRowOfAnUnknownServerWithTheSameUsernameKeepsItsRowsAndTheLoginIsSaved() {
        fillEveryTable(staleId, "unknown", 10)
        forgetTheServerOf(staleId)
        val ownersBefore = owners()
        val songsBefore = songs()
        val albumsBefore = albums()
        val artistsBefore = artists()
        val gamesBefore = savedGames()

        saveCurrent()

        assertEquals(
            ownersBefore + ("users" to setOf<List<Any>>(listOf(staleId, "mac"), listOf(currentId, "mac"), listOf(otherId, "other"))),
            owners()
        )
        assertEquals(songsBefore, songs())
        assertEquals(albumsBefore, albums())
        assertEquals(artistsBefore, artists())
        assertEquals(gamesBefore, savedGames())
        assertEquals(currentId, runBlocking { repository.getUser(currentId) }!!.id)
    }

    @Test
    fun aRowOfAnUnknownServerIsTakenOverOnceItsOwnLoginAttributedIt() {
        fillEveryTable(staleId, "first", 10)
        forgetTheServerOf(staleId)
        runBlocking { repository.saveUser(User(id = staleId, username = "mac")) }

        saveCurrent()

        assertEquals(
            mapOf(
                "users" to setOf<List<Any>>(listOf(currentId, "mac"), listOf(otherId, "other")),
                "playlists" to setOf<List<Any>>(listOf(currentId, "first")),
                "scrobbles" to setOf<List<Any>>(listOf(currentId, "first")),
                "history" to setOf<List<Any>>(listOf(currentId, "first")),
                "games" to setOf<List<Any>>(listOf(currentId, "first"))
            ),
            owners()
        )
    }
}
