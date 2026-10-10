package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppJson
import dev.dertyp.synara.game.GameConfig
import dev.dertyp.synara.game.LeaderboardEntry
import dev.dertyp.synara.game.PoolSource
import dev.dertyp.synara.game.RoundResult
import dev.dertyp.synara.game.SavedGame
import dev.dertyp.synara.game.SnippetStart
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

class ExposedSongGuessRepositoryTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private lateinit var repository: ExposedSongGuessRepository

    private val userId = randomPlatformUUID()
    private val otherUserId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-song-guess", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(DownloadedImages, DownloadedUsers, SongGuessGames, SongGuessSavedGames)
            listOf(userId, otherUserId).forEach { user ->
                DownloadedUsers.insert {
                    it[id] = user
                    it[username] = user.toString()
                    it[passwordHash] = ""
                }
            }
        }
        repository = ExposedSongGuessRepository(AppJson)
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun config(rounds: Int = 5) = GameConfig(
        source = PoolSource.PLAYLISTS,
        playlistIds = listOf(randomPlatformUUID(), randomPlatformUUID()),
        artistIds = listOf(randomPlatformUUID()),
        rounds = rounds,
        snippetStart = SnippetStart.RANDOM
    )

    private fun results(count: Int) = List(count) {
        RoundResult(
            songId = randomPlatformUUID(),
            title = "Song $it",
            artist = "Artist $it",
            coverId = if (it % 2 == 0) randomPlatformUUID() else null,
            attemptsUsed = it + 1,
            solved = it % 2 == 0,
            points = 6 - it
        )
    }

    private fun entry(score: Int, playedAt: Long, rounds: Int = 5) = LeaderboardEntry(
        playedAt = playedAt,
        score = score,
        maxScore = rounds * 6,
        config = config(rounds),
        roundResults = results(rounds)
    )

    private fun savedGame(roundIndex: Int, score: Int) = SavedGame(
        config = config(),
        roundIndex = roundIndex,
        attempt = 2,
        score = score,
        roundResults = results(roundIndex),
        currentSongId = randomPlatformUUID(),
        snippetOffsetMs = 12_345,
        inReveal = true
    )

    private fun savedGameRows(): Long = transaction(database) {
        SongGuessSavedGames.selectAll().where { SongGuessSavedGames.userId eq userId }.count()
    }

    private fun leaderboardRows(user: PlatformUUID): Long = transaction(database) {
        SongGuessGames.selectAll().where { SongGuessGames.userId eq user }.count()
    }

    @Test
    fun leaderboardIsOrderedByScoreThenNewestPlayedAt() = runBlocking {
        repository.insert(userId, entry(score = 3, playedAt = 9))
        repository.insert(userId, entry(score = 9, playedAt = 1))
        repository.insert(userId, entry(score = 5, playedAt = 1))
        repository.insert(userId, entry(score = 9, playedAt = 2))

        val order = repository.getAll(userId).map { it.score to it.playedAt }

        assertEquals(listOf(9 to 2L, 9 to 1L, 5 to 1L, 3 to 9L), order)
    }

    @Test
    fun insertReturnsTheEntryWithItsGeneratedId() = runBlocking {
        val first = repository.insert(userId, entry(score = 4, playedAt = 1))
        val second = repository.insert(userId, entry(score = 5, playedAt = 2))

        assertTrue(first.id > 0)
        assertTrue(second.id > 0)
        assertTrue(first.id != second.id)
        assertEquals(setOf(first.id, second.id), repository.getAll(userId).map { it.id }.toSet())
    }

    @Test
    fun configAndRoundResultsRoundTrip() = runBlocking {
        val original = entry(score = 17, playedAt = 123, rounds = 10)

        val inserted = repository.insert(userId, original)

        assertEquals(original.copy(id = inserted.id), inserted)
        assertEquals(listOf(inserted), repository.getAll(userId))
        assertEquals(10, repository.getAll(userId).single().config.rounds)
    }

    @Test
    fun leaderboardOfAnotherUserIsNotMixedIn() = runBlocking {
        repository.insert(userId, entry(score = 1, playedAt = 1))
        repository.insert(otherUserId, entry(score = 2, playedAt = 2))

        assertEquals(listOf(1), repository.getAll(userId).map { it.score })
        assertEquals(listOf(2), repository.getAll(otherUserId).map { it.score })
    }

    @Test
    fun clearRemovesOnlyThatUsersGames() = runBlocking {
        repository.insert(userId, entry(score = 1, playedAt = 1))
        repository.insert(otherUserId, entry(score = 2, playedAt = 2))

        repository.clear(userId)

        assertTrue(repository.getAll(userId).isEmpty())
        assertEquals(listOf(2), repository.getAll(otherUserId).map { it.score })
    }

    @Test
    fun savingAGameTwiceKeepsOnlyTheSecond() = runBlocking {
        val first = savedGame(roundIndex = 1, score = 3)
        val second = savedGame(roundIndex = 2, score = 8)

        repository.saveGame(userId, first)
        repository.saveGame(userId, second)

        assertEquals(second, repository.loadGame(userId))
        assertEquals(1L, savedGameRows())
    }

    @Test
    fun savedGamesAreKeptPerUser() = runBlocking {
        val own = savedGame(roundIndex = 1, score = 3)
        val foreign = savedGame(roundIndex = 4, score = 11)

        repository.saveGame(userId, own)
        repository.saveGame(otherUserId, foreign)

        assertEquals(own, repository.loadGame(userId))
        assertEquals(foreign, repository.loadGame(otherUserId))
    }

    @Test
    fun loadingWithoutASavedGameReturnsNull() = runBlocking {
        assertNull(repository.loadGame(userId))
    }

    @Test
    fun clearGameRemovesTheSavedGame() = runBlocking {
        repository.saveGame(userId, savedGame(roundIndex = 1, score = 3))

        repository.clearGame(userId)

        assertNull(repository.loadGame(userId))
        assertEquals(0L, savedGameRows())
    }

    @Test
    fun clearGameLeavesTheLeaderboardRows() = runBlocking {
        repository.insert(userId, entry(score = 6, playedAt = 1))
        repository.saveGame(userId, savedGame(roundIndex = 1, score = 3))

        repository.clearGame(userId)

        assertEquals(1L, leaderboardRows(userId))
        assertEquals(listOf(6), repository.getAll(userId).map { it.score })
    }
}
