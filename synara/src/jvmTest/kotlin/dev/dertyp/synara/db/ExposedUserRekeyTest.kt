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

class ExposedUserRekeyTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedUserRepository

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
        repository = ExposedUserRepository()
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
}
