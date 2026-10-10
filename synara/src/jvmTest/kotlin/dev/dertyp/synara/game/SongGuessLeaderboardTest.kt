package dev.dertyp.synara.game

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.SongGuessRepository
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingSongGuessRepository : SongGuessRepository {
    val entries = mutableMapOf<PlatformUUID, List<LeaderboardEntry>>()
    val games = mutableMapOf<PlatformUUID, SavedGame>()
    var calls = 0
    var failInsert = false
    var failGetAll = false
    private var nextId = 1L

    override suspend fun insert(userId: PlatformUUID, entry: LeaderboardEntry): LeaderboardEntry {
        calls++
        if (failInsert) throw IOException("insert")
        val stored = entry.copy(id = nextId++)
        entries[userId] = entries[userId].orEmpty() + stored
        return stored
    }

    override suspend fun getAll(userId: PlatformUUID): List<LeaderboardEntry> {
        calls++
        if (failGetAll) throw IOException("getAll")
        return entries[userId].orEmpty()
    }

    override suspend fun clear(userId: PlatformUUID) {
        calls++
        entries.remove(userId)
    }

    override suspend fun saveGame(userId: PlatformUUID, game: SavedGame) {
        calls++
        games[userId] = game
    }

    override suspend fun loadGame(userId: PlatformUUID): SavedGame? {
        calls++
        return games[userId]
    }

    override suspend fun clearGame(userId: PlatformUUID) {
        calls++
        games.remove(userId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SongGuessLeaderboardTest {

    private val userA = User(id = randomPlatformUUID(), username = "a")
    private val userB = User(id = randomPlatformUUID(), username = "b")
    private val userFlow = MutableStateFlow<User?>(null)
    private val globalState = mockk<GlobalStateModel> {
        every { user } returns userFlow
    }
    private val repository = RecordingSongGuessRepository()

    private fun TestScope.createLeaderboard(): SongGuessLeaderboard {
        val leaderboard = SongGuessLeaderboard(
            repository,
            globalState,
            mockk<Logger>(relaxed = true),
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return leaderboard
    }

    private fun entry(score: Int, id: Long = 0L) = LeaderboardEntry(
        id = id,
        playedAt = 1_000L + score,
        score = score,
        maxScore = 12,
        config = GameConfig(rounds = 2),
        roundResults = emptyList()
    )

    private fun savedGame() = SavedGame(
        config = GameConfig(rounds = 2),
        roundIndex = 1,
        attempt = 2,
        score = 5,
        roundResults = emptyList(),
        currentSongId = randomPlatformUUID(),
        snippetOffsetMs = 0L,
        inReveal = false
    )

    @Test
    fun addInsertsUnderTheCurrentUserAndEntriesEqualTheRepositoryList() = runTest {
        userFlow.value = userA
        val leaderboard = createLeaderboard()

        leaderboard.add(entry(score = 4))
        runCurrent()

        val stored = repository.entries.getValue(userA.id)
        assertEquals(listOf(4), stored.map { it.score })
        assertNull(repository.entries[userB.id])
        assertEquals(stored, leaderboard.entries.value)
    }

    @Test
    fun switchingTheUserShowsTheEntriesOfTheNewUser() = runTest {
        repository.entries[userA.id] = listOf(entry(score = 1, id = 1L))
        repository.entries[userB.id] = listOf(entry(score = 2, id = 2L), entry(score = 3, id = 3L))
        userFlow.value = userA
        val leaderboard = createLeaderboard()
        assertEquals(repository.entries.getValue(userA.id), leaderboard.entries.value)

        userFlow.value = userB
        runCurrent()

        assertEquals(repository.entries.getValue(userB.id), leaderboard.entries.value)
    }

    @Test
    fun logoutEmptiesTheEntries() = runTest {
        repository.entries[userA.id] = listOf(entry(score = 1, id = 1L))
        userFlow.value = userA
        val leaderboard = createLeaderboard()
        assertEquals(1, leaderboard.entries.value.size)

        userFlow.value = null
        runCurrent()

        assertTrue(leaderboard.entries.value.isEmpty())
    }

    @Test
    fun withoutAUserAddAndSaveGameNeverTouchTheRepository() = runTest {
        val leaderboard = createLeaderboard()

        leaderboard.add(entry(score = 4))
        leaderboard.saveGame(savedGame())
        runCurrent()

        assertEquals(0, repository.calls)
        assertTrue(repository.entries.isEmpty())
        assertTrue(repository.games.isEmpty())
        assertTrue(leaderboard.entries.value.isEmpty())
    }

    @Test
    fun aFailingInsertLeavesTheEntriesUnchanged() = runTest {
        val existing = listOf(entry(score = 1, id = 1L))
        repository.entries[userA.id] = existing
        userFlow.value = userA
        val leaderboard = createLeaderboard()
        repository.failInsert = true

        leaderboard.add(entry(score = 4))
        runCurrent()

        assertEquals(existing, leaderboard.entries.value)
        assertEquals(existing, repository.entries.getValue(userA.id))
    }

    @Test
    fun loadGameBeforeLoginCompletesOnceTheUserAppears() = runTest {
        val game = savedGame()
        repository.games[userA.id] = game
        val leaderboard = createLeaderboard()

        val loaded = async { leaderboard.loadGame() }
        runCurrent()
        assertFalse(loaded.isCompleted)

        userFlow.value = userA
        runCurrent()

        assertTrue(loaded.isCompleted)
        assertEquals(game, loaded.await())
    }

    @Test
    fun aFailingReloadKeepsThePreviousEntries() = runTest {
        val existing = listOf(entry(score = 1, id = 1L))
        repository.entries[userA.id] = existing
        userFlow.value = userA
        val leaderboard = createLeaderboard()
        repository.failGetAll = true

        leaderboard.add(entry(score = 4))
        runCurrent()

        assertEquals(2, repository.entries.getValue(userA.id).size)
        assertEquals(existing, leaderboard.entries.value)
    }
}
