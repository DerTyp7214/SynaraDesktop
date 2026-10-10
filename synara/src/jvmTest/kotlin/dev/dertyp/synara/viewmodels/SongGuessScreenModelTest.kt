package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.db.SongGuessRepository
import dev.dertyp.synara.game.GameAudioPlayer
import dev.dertyp.synara.game.GameConfig
import dev.dertyp.synara.game.LeaderboardEntry
import dev.dertyp.synara.game.PoolSource
import dev.dertyp.synara.game.RoundResult
import dev.dertyp.synara.game.SavedGame
import dev.dertyp.synara.game.SnippetStart
import dev.dertyp.synara.game.SongGuessLeaderboard
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.FakeAudioPlayer
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.viewmodels.SongGuessScreenModel.Phase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class RepositoryCall { INSERT, GET_ALL, CLEAR, SAVE_GAME, LOAD_GAME, CLEAR_GAME }

private class InMemorySongGuessRepository : SongGuessRepository {
    val entries = mutableMapOf<PlatformUUID, List<LeaderboardEntry>>()
    val games = mutableMapOf<PlatformUUID, SavedGame>()
    val calls = mutableListOf<RepositoryCall>()
    private var nextId = 1L

    fun count(call: RepositoryCall) = calls.count { it == call }

    override suspend fun insert(userId: PlatformUUID, entry: LeaderboardEntry): LeaderboardEntry {
        calls += RepositoryCall.INSERT
        val stored = entry.copy(id = nextId++)
        entries[userId] = entries[userId].orEmpty() + stored
        return stored
    }

    override suspend fun getAll(userId: PlatformUUID): List<LeaderboardEntry> {
        calls += RepositoryCall.GET_ALL
        return entries[userId].orEmpty()
    }

    override suspend fun clear(userId: PlatformUUID) {
        calls += RepositoryCall.CLEAR
        entries.remove(userId)
    }

    override suspend fun saveGame(userId: PlatformUUID, game: SavedGame) {
        calls += RepositoryCall.SAVE_GAME
        games[userId] = game
    }

    override suspend fun loadGame(userId: PlatformUUID): SavedGame? {
        calls += RepositoryCall.LOAD_GAME
        return games[userId]
    }

    override suspend fun clearGame(userId: PlatformUUID) {
        calls += RepositoryCall.CLEAR_GAME
        games.remove(userId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SongGuessScreenModelTest {

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(User(id = userId, username = "player"))
    private val playlistFlow = MutableStateFlow<List<UserPlaylist>>(emptyList())
    private val globalState = mockk<GlobalStateModel> {
        every { user } returns userFlow
        every { userPlaylists } returns playlistFlow
    }
    private val songService = mockk<ISongService>()
    private val artistService = mockk<IArtistService>()
    private val userPlaylistService = mockk<IUserPlaylistService>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }
    private val playerModel = mockk<PlayerModel>(relaxed = true) {
        every { volume } returns MutableStateFlow(1f)
    }
    private val player = spyk(FakeAudioPlayer())
    private val repository = InMemorySongGuessRepository()
    private lateinit var leaderboard: SongGuessLeaderboard
    private var songIndex = 0

    private fun TestScope.createModel(): SongGuessScreenModel {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        leaderboard = SongGuessLeaderboard(repository, globalState, mockk<Logger>(relaxed = true), dispatchers)
        val model = SongGuessScreenModel(
            songService,
            artistService,
            userPlaylistService,
            globalState,
            rpcServiceManager,
            dispatchers,
            leaderboard,
            playerModel,
            GameAudioPlayer(player)
        )
        runCurrent()
        return model
    }

    private fun stubLikedPool(total: Int, song: () -> UserSong = { testSong(songIndex++) }) {
        coEvery { songService.likedSongs(any(), 1, true) } answers {
            PaginatedResponse(data = listOf(song()), total = total)
        }
    }

    private fun TestScope.startedGame(
        rounds: Int = 2,
        total: Int = 5,
        song: () -> UserSong = { testSong(songIndex++) }
    ): SongGuessScreenModel {
        stubLikedPool(total, song)
        val model = createModel()
        model.setRounds(rounds)
        model.startGame()
        runCurrent()
        return model
    }

    private fun TestScope.modelWithFailedStart(): SongGuessScreenModel {
        val model = startedGame(rounds = 10, total = 5)
        assertEquals(5, model.state.value.poolTotal)
        assertEquals(SongGuessScreenModel.ERROR_NOT_ENOUGH_SONGS, model.state.value.error)
        return model
    }

    private fun SongGuessScreenModel.currentSong(): UserSong = assertNotNull(state.value.currentSong)

    private fun playlist(index: Int) = UserPlaylist(
        id = randomPlatformUUID(),
        name = "Playlist $index",
        songs = emptyList(),
        creator = userId,
        description = ""
    )

    private fun leaderboardEntry(playlistIds: List<PlatformUUID>, artistIds: List<PlatformUUID>, playedAt: Long) =
        LeaderboardEntry(
            playedAt = playedAt,
            score = 0,
            maxScore = 6,
            config = GameConfig(playlistIds = playlistIds, artistIds = artistIds, rounds = 1),
            roundResults = emptyList()
        )

    @Test
    fun aCorrectGuessOnTheFirstAttemptRevealsWithFullPoints() = runTest {
        val model = startedGame(rounds = 2, total = 5)
        assertEquals(Phase.PLAYING, model.state.value.phase)
        val song = model.currentSong()

        model.selectGuess(song)
        model.submitGuess()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.REVEAL, state.phase)
        assertEquals(6, state.score)
        assertEquals(6, state.lastRoundPoints)
        val result = state.roundResults.single()
        assertEquals(song.id, result.songId)
        assertTrue(result.solved)
        assertEquals(1, result.attemptsUsed)
        assertEquals(6, result.points)
    }

    @Test
    fun aCorrectGuessOnTheThirdAttemptScoresFourPoints() = runTest {
        val model = startedGame(rounds = 2, total = 5)
        val song = model.currentSong()
        repeat(2) { model.skip() }
        runCurrent()
        assertEquals(2, model.state.value.attempt)
        assertEquals(Phase.PLAYING, model.state.value.phase)

        model.selectGuess(song)
        model.submitGuess()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.REVEAL, state.phase)
        assertEquals(4, state.score)
        assertEquals(4, state.lastRoundPoints)
        val result = state.roundResults.single()
        assertEquals(song.id, result.songId)
        assertTrue(result.solved)
        assertEquals(3, result.attemptsUsed)
        assertEquals(4, result.points)
    }

    @Test
    fun aSecondSubmitDuringTheRevealAddsNoResultAndNoPoints() = runTest {
        val model = startedGame(rounds = 2, total = 5)
        val song = model.currentSong()
        model.selectGuess(song)
        model.submitGuess()
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        assertEquals(6, model.state.value.score)

        model.submitGuess()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.REVEAL, state.phase)
        assertEquals(1, state.roundResults.size)
        assertEquals(song.id, state.roundResults.single().songId)
        assertEquals(6, state.score)
        assertEquals(6, state.lastRoundPoints)
        assertEquals(0, state.roundIndex)
    }

    @Test
    fun aSecondNextRoundWhileTheNextRoundIsStillStartingAdvancesOneRound() = runTest {
        val model = startedGame(rounds = 3, total = 5)
        val first = model.currentSong()
        model.selectGuess(first)
        model.submitGuess()
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        assertEquals(listOf(first.id to false), player.loads.value)
        val gate = CompletableDeferred<Unit>()
        coEvery { songService.likedSongs(any(), 1, true) } coAnswers {
            gate.await()
            PaginatedResponse(data = listOf(testSong(songIndex++)), total = 5)
        }

        model.nextRound()
        runCurrent()
        model.nextRound()
        runCurrent()

        assertEquals(1, model.state.value.roundIndex)

        gate.complete(Unit)
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.PLAYING, state.phase)
        assertEquals(1, state.roundIndex)
        assertEquals(1, state.roundResults.size)
        assertEquals(6, state.score)
        val second = model.currentSong()
        assertEquals(listOf(first.id to false, second.id to false), player.loads.value)
        assertEquals(0, repository.count(RepositoryCall.INSERT))
        assertEquals(1, repository.games.getValue(userId).roundIndex)
    }

    @Test
    fun disposingTheScreenWhileTheNextRoundStartsKeepsTheSavedGame() = runTest {
        val model = startedGame(rounds = 3, total = 5)
        model.selectGuess(model.currentSong())
        model.submitGuess()
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        val gate = CompletableDeferred<Unit>()
        coEvery { songService.likedSongs(any(), 1, true) } coAnswers {
            gate.await()
            PaginatedResponse(data = listOf(testSong(songIndex++)), total = 5)
        }
        model.nextRound()
        runCurrent()

        model.onDispose()
        gate.completeExceptionally(CancellationException("disposed"))
        runCurrent()

        assertEquals(0, repository.count(RepositoryCall.INSERT))
        assertEquals(0, repository.count(RepositoryCall.CLEAR_GAME))
        assertTrue(leaderboard.entries.value.isEmpty())
        val saved = assertNotNull(repository.games[userId])
        assertEquals(0, saved.roundIndex)
        assertTrue(saved.inReveal)
        assertEquals(1, saved.roundResults.size)
    }

    @Test
    fun revealControlsDoNothingWhileTheNextRoundIsStillStarting() = runTest {
        val model = startedGame(rounds = 3, total = 5)
        model.selectGuess(model.currentSong())
        model.submitGuess()
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        val gate = CompletableDeferred<Unit>()
        coEvery { songService.likedSongs(any(), 1, true) } coAnswers {
            gate.await()
            PaginatedResponse(data = listOf(testSong(songIndex++)), total = 5)
        }
        model.nextRound()
        runCurrent()
        val playsBefore = player.plays.value
        val seeksBefore = player.seeks.value
        val positionBefore = player.position.value
        assertFalse(player.playing.value)

        model.toggleRevealPlayback()
        model.seekReveal(42_000L)
        runCurrent()

        assertEquals(playsBefore, player.plays.value)
        assertEquals(seeksBefore, player.seeks.value)
        assertEquals(positionBefore, player.position.value)
        assertFalse(player.playing.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertFalse(player.playing.value)
    }

    @Test
    fun revealControlsPauseResumeAndSeekTheRevealedSong() = runTest {
        val model = startedGame(rounds = 3, total = 5)
        model.selectGuess(model.currentSong())
        model.submitGuess()
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        assertTrue(player.playing.value)

        model.toggleRevealPlayback()
        runCurrent()
        assertFalse(player.playing.value)
        assertFalse(model.state.value.revealIsPlaying)

        model.toggleRevealPlayback()
        model.seekReveal(42_000L)
        runCurrent()

        assertTrue(player.playing.value)
        assertEquals(42_000L, player.position.value)
        assertEquals(42_000L, model.state.value.revealPosition)
    }

    @Test
    fun aPoolExactlyAsLargeAsTheRoundCountPlaysEveryRoundWithADifferentSong() = runTest {
        val pool = List(20) { testSong(it) }
        val requested = mutableListOf<Int>()
        coEvery { songService.likedSongs(any(), 1, true) } answers {
            val index = firstArg<Int>()
            requested += index
            PaginatedResponse(data = listOfNotNull(pool.getOrNull(index)), total = pool.size)
        }
        val model = createModel()
        model.setRounds(pool.size)
        model.startGame()
        runCurrent()

        repeat(pool.size) {
            assertEquals(Phase.PLAYING, model.state.value.phase)
            repeat(6) { model.skip() }
            runCurrent()
            assertEquals(Phase.REVEAL, model.state.value.phase)
            model.nextRound()
            runCurrent()
        }

        val state = model.state.value
        assertEquals(Phase.FINISHED, state.phase)
        assertNull(state.error)
        assertEquals(pool.size, state.roundResults.size)
        assertEquals(pool.map { it.id }.toSet(), state.roundResults.map { it.songId }.toSet())
        assertEquals(pool.indices.toList(), requested.drop(1).sorted())
        assertEquals(pool.size, repository.entries.getValue(userId).single().roundResults.size)
    }

    @Test
    fun aSongUnderTheMinimumLengthIsTriedAtMostOnceAndEveryRoundIsStillFilled() = runTest {
        val short = testSong(0, duration = 5_000)
        val pool = listOf(testSong(1), short, testSong(2), testSong(3))
        val requested = mutableListOf<Int>()
        coEvery { songService.likedSongs(any(), 1, true) } answers {
            val index = firstArg<Int>()
            requested += index
            PaginatedResponse(data = listOfNotNull(pool.getOrNull(index)), total = pool.size)
        }
        val model = createModel()
        model.setRounds(3)
        model.startGame()
        runCurrent()

        repeat(3) {
            assertEquals(Phase.PLAYING, model.state.value.phase)
            repeat(6) { model.skip() }
            runCurrent()
            model.nextRound()
            runCurrent()
        }

        val state = model.state.value
        assertEquals(Phase.FINISHED, state.phase)
        assertNull(state.error)
        assertEquals((pool - short).map { it.id }.toSet(), state.roundResults.map { it.songId }.toSet())
        val drawn = requested.drop(1)
        assertEquals(drawn.size, drawn.toSet().size)
    }

    @Test
    fun fiveSkipsKeepPlayingOnTheLastAttemptAndTheSixthRevealsWithoutPoints() = runTest {
        val model = startedGame()

        repeat(5) { model.skip() }
        runCurrent()
        assertEquals(5, model.state.value.attempt)
        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertTrue(model.state.value.roundResults.isEmpty())

        model.skip()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.REVEAL, state.phase)
        assertEquals(0, state.score)
        assertEquals(0, state.lastRoundPoints)
        val result = state.roundResults.single()
        assertFalse(result.solved)
        assertEquals(6, result.attemptsUsed)
        assertEquals(0, result.points)
    }

    @Test
    fun aGuessWithAnotherIdButTheSameTitleAndArtistsUpToCaseAndAccentsCounts() = runTest {
        val model = startedGame {
            testSong(songIndex++, artists = listOf(ArtistCredit(randomPlatformUUID(), "Beyoncé", false)))
                .copy(title = "Déjà Vu")
        }
        val song = model.currentSong()
        val guess = testSong(900, artists = listOf(ArtistCredit(randomPlatformUUID(), "BEYONCE", false)))
            .copy(title = "deja vu")
        assertTrue(guess.id != song.id)

        model.selectGuess(guess)
        model.submitGuess()
        runCurrent()

        assertEquals(Phase.REVEAL, model.state.value.phase)
        assertTrue(model.state.value.roundResults.single().solved)
        assertEquals(6, model.state.value.score)
    }

    @Test
    fun aWrongGuessClearsQueryAndSelectionMarksItWrongAndSavesTheNewAttempt() = runTest {
        val model = startedGame()
        assertEquals(0, repository.games.getValue(userId).attempt)
        val savesBefore = repository.count(RepositoryCall.SAVE_GAME)

        model.selectGuess(testSong(900))
        assertNotNull(model.state.value.selectedGuess)
        assertTrue(model.state.value.guessQuery.isNotEmpty())
        model.submitGuess()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.PLAYING, state.phase)
        assertEquals(1, state.attempt)
        assertTrue(state.lastGuessWrong)
        assertTrue(state.guessQuery.isEmpty())
        assertNull(state.selectedGuess)
        assertTrue(state.suggestions.isEmpty())
        assertEquals(savesBefore + 1, repository.count(RepositoryCall.SAVE_GAME))
        assertEquals(1, repository.games.getValue(userId).attempt)
    }

    @Test
    fun finishingTheLastRevealAddsOneLeaderboardEntryAndClearsTheSavedGame() = runTest {
        val model = startedGame(rounds = 2, total = 5)
        model.selectGuess(model.currentSong())
        model.submitGuess()
        runCurrent()
        model.nextRound()
        runCurrent()
        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertEquals(1, model.state.value.roundIndex)
        repeat(6) { model.skip() }
        runCurrent()
        assertEquals(Phase.REVEAL, model.state.value.phase)
        assertEquals(0, repository.count(RepositoryCall.INSERT))
        assertEquals(0, repository.count(RepositoryCall.CLEAR_GAME))

        model.nextRound()
        runCurrent()

        assertEquals(Phase.FINISHED, model.state.value.phase)
        assertNull(model.state.value.error)
        assertEquals(1, repository.count(RepositoryCall.INSERT))
        val entry = repository.entries.getValue(userId).single()
        assertEquals(6, entry.score)
        assertEquals(12, entry.maxScore)
        assertEquals(2, entry.roundResults.size)
        assertEquals(listOf(entry), leaderboard.entries.value)
        assertEquals(1, repository.count(RepositoryCall.CLEAR_GAME))
        assertNull(repository.games[userId])
    }

    @Test
    fun aPoolSmallerThanTheRoundCountReturnsToSetupWithTheErrorAndNeverLoads() = runTest {
        val model = startedGame(rounds = 10, total = 5)

        val state = model.state.value
        assertEquals(Phase.SETUP, state.phase)
        assertEquals(SongGuessScreenModel.ERROR_NOT_ENOUGH_SONGS, state.error)
        assertEquals(5, state.poolTotal)
        assertNull(state.currentSong)
        assertTrue(player.loads.value.isEmpty())
        assertEquals(0, repository.count(RepositoryCall.SAVE_GAME))
    }

    @Test
    fun aRandomSnippetStartLiesBetweenTheAudibleStartAndTheTailLimit() = runTest {
        stubLikedPool(total = 5) { testSong(songIndex++, duration = 200_000).copy(audioStartMs = 5000L) }
        val model = createModel()
        model.setRounds(1)
        model.setSnippetStart(SnippetStart.RANDOM)

        model.startGame()
        runCurrent()

        assertEquals(Phase.PLAYING, model.state.value.phase)
        val offset = model.state.value.snippetOffsetMs
        assertTrue(offset >= 4950L)
        assertTrue(offset < 170_000L)
        assertEquals(offset, player.position.value)
    }

    @Test
    fun aRandomSnippetStartOfASongOneMillisecondLongerThanTheTailIsZeroWithoutASeek() = runTest {
        stubLikedPool(total = 5) { testSong(songIndex++, duration = 30_001) }
        val model = createModel()
        model.setRounds(1)
        model.setSnippetStart(SnippetStart.RANDOM)

        model.startGame()
        runCurrent()

        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertNull(model.state.value.error)
        assertNull(model.currentSong().audioStartMs)
        assertEquals(0L, model.state.value.snippetOffsetMs)
        assertEquals(listOf(model.currentSong().id to false), player.loads.value)
        assertEquals(0, player.seeks.value)
        assertEquals(0L, player.position.value)
    }

    @Test
    fun aRandomSnippetStartOfASongShorterThanTheTailIsTheAudibleStartFloor() = runTest {
        stubLikedPool(total = 5) { testSong(songIndex++, duration = 20_000).copy(audioStartMs = 5000L) }
        val model = createModel()
        model.setRounds(1)
        model.setSnippetStart(SnippetStart.RANDOM)

        model.startGame()
        runCurrent()

        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertNull(model.state.value.error)
        assertEquals(4950L, model.state.value.snippetOffsetMs)
        assertEquals(listOf(model.currentSong().id to false), player.loads.value)
        assertEquals(1, player.seeks.value)
        assertEquals(4950L, player.position.value)
    }

    @Test
    fun aRandomSnippetStartOfASongShorterThanTheTailWithoutLeadingSilenceIsZeroWithoutASeek() = runTest {
        stubLikedPool(total = 5) { testSong(songIndex++, duration = 20_000) }
        val model = createModel()
        model.setRounds(1)
        model.setSnippetStart(SnippetStart.RANDOM)

        model.startGame()
        runCurrent()

        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertNull(model.state.value.error)
        assertEquals(0L, model.state.value.snippetOffsetMs)
        assertEquals(0, player.seeks.value)
        assertEquals(0L, player.position.value)
    }

    @Test
    fun aSongStartSnippetBeginsJustBeforeTheAudibleStartWithOneSeek() = runTest {
        val model = startedGame(rounds = 1) {
            testSong(songIndex++, duration = 200_000).copy(audioStartMs = 5000L)
        }

        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertEquals(4950L, model.state.value.snippetOffsetMs)
        assertEquals(listOf(model.currentSong().id to false), player.loads.value)
        assertEquals(1, player.seeks.value)
        assertEquals(4950L, player.position.value)
        assertEquals(0, player.plays.value)
    }

    @Test
    fun theSnippetOfTheThirdAttemptPausesOnceThePositionReachesItsLength() = runTest {
        val model = startedGame(rounds = 1) {
            testSong(songIndex++, duration = 200_000).copy(audioStartMs = 5000L)
        }
        repeat(2) { model.skip() }
        runCurrent()
        assertEquals(2, model.state.value.attempt)
        verify(exactly = 2) { player.pause() }
        val startedAt = currentTime

        model.playSnippet()
        runCurrent()
        assertTrue(model.state.value.isSnippetPlaying)
        assertEquals(0f, model.state.value.snippetProgress)
        assertEquals(1, player.plays.value)
        assertTrue(player.playing.value)

        player.position.value = 4950L + 999L
        runCurrent()
        assertTrue(model.state.value.isSnippetPlaying)
        assertTrue(model.state.value.snippetProgress < 1f)
        verify(exactly = 2) { player.pause() }

        player.position.value = 4950L + 1000L
        runCurrent()

        verify(exactly = 3) { player.pause() }
        assertFalse(model.state.value.isSnippetPlaying)
        assertEquals(1f, model.state.value.snippetProgress)
        assertFalse(player.playing.value)
        assertEquals(startedAt, currentTime)
    }

    @Test
    fun aSnippetWhosePositionNeverMovesPausesAfterFiveSeconds() = runTest {
        val model = startedGame(rounds = 1)
        repeat(2) { model.skip() }
        runCurrent()
        verify(exactly = 2) { player.pause() }

        model.playSnippet()
        runCurrent()
        advanceTimeBy(4999)
        runCurrent()
        assertTrue(model.state.value.isSnippetPlaying)
        assertTrue(player.playing.value)
        verify(exactly = 2) { player.pause() }

        advanceTimeBy(1)
        runCurrent()

        verify(exactly = 3) { player.pause() }
        assertFalse(model.state.value.isSnippetPlaying)
        assertFalse(player.playing.value)
        assertEquals(Phase.PLAYING, model.state.value.phase)
    }

    @Test
    fun aOneCharacterGuessQueryNeverSearches() = runTest {
        val model = createModel()

        model.updateGuess("a")
        advanceTimeBy(1000)
        runCurrent()

        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        assertTrue(model.state.value.suggestions.isEmpty())
    }

    @Test
    fun twoGuessQueriesWithinTheDebounceSearchOnceWithTheLastQueryAmongLikedSongs() = runTest {
        val found = listOf(testSong(1), testSong(2))
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } returns
            PaginatedResponse(data = found, total = found.size)
        val model = createModel()
        assertEquals(PoolSource.LIKED, model.state.value.config.source)

        model.updateGuess("ab")
        advanceTimeBy(200)
        runCurrent()
        model.updateGuess("abc")
        advanceTimeBy(299)
        runCurrent()
        coVerify(exactly = 0) { songService.rankedSearch(any(), any(), any(), any(), any()) }

        advanceTimeBy(1)
        runCurrent()

        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { songService.rankedSearch(0, 8, "abc", true, true) }
        assertEquals(found, model.state.value.suggestions)
    }

    @Test
    fun theGuessSearchIsNotLimitedToLikedSongsForAnotherSource() = runTest {
        coEvery { songService.rankedSearch(any(), any(), any(), any(), any()) } returns
            PaginatedResponse(data = emptyList(), total = 0)
        val model = createModel()
        model.setSource(PoolSource.ALL)

        model.updateGuess("ab")
        advanceTimeBy(300)
        runCurrent()

        coVerify(exactly = 1) { songService.rankedSearch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { songService.rankedSearch(0, 8, "ab", true, false) }
    }

    @Test
    fun togglePlaylistAddsThenRemovesTheIdAndResetsThePoolTotal() = runTest {
        val model = modelWithFailedStart()
        val id = randomPlatformUUID()

        model.togglePlaylist(id)
        assertEquals(listOf(id), model.state.value.config.playlistIds)
        assertNull(model.state.value.poolTotal)

        model.togglePlaylist(id)
        assertTrue(model.state.value.config.playlistIds.isEmpty())
    }

    @Test
    fun setAllPlaylistsSelectsEveryUserPlaylistOrNoneAndResetsThePoolTotal() = runTest {
        val playlists = listOf(playlist(1), playlist(2))
        playlistFlow.value = playlists
        val model = modelWithFailedStart()

        model.setAllPlaylists(true)
        assertEquals(playlists.map { it.id }, model.state.value.config.playlistIds)
        assertNull(model.state.value.poolTotal)

        model.setAllPlaylists(false)
        assertTrue(model.state.value.config.playlistIds.isEmpty())
    }

    @Test
    fun addArtistIgnoresAnArtistThatIsAlreadySelectedAndResetsThePoolTotal() = runTest {
        val model = modelWithFailedStart()
        val artist = Artist(randomPlatformUUID(), "Artist", false)

        model.addArtist(artist)
        model.addArtist(artist.copy(name = "Renamed"))

        assertEquals(listOf(artist), model.state.value.selectedArtists)
        assertEquals(listOf(artist.id), model.state.value.config.artistIds)
        assertNull(model.state.value.poolTotal)
    }

    @Test
    fun removeArtistDropsTheArtistAndItsIdAndResetsThePoolTotal() = runTest {
        val kept = Artist(randomPlatformUUID(), "Kept", false)
        val removed = Artist(randomPlatformUUID(), "Removed", false)
        val model = createModel()
        model.addArtist(kept)
        model.addArtist(removed)
        coEvery { songService.likedByArtist(any(), 1, any(), true) } answers {
            PaginatedResponse(data = listOf(testSong(songIndex++)), total = 1)
        }
        model.setRounds(10)
        model.startGame()
        runCurrent()
        assertEquals(2, model.state.value.poolTotal)

        model.removeArtist(removed.id)

        assertEquals(listOf(kept), model.state.value.selectedArtists)
        assertEquals(listOf(kept.id), model.state.value.config.artistIds)
        assertNull(model.state.value.poolTotal)
    }

    @Test
    fun setSourceResetsThePoolTotalAndTheError() = runTest {
        val model = modelWithFailedStart()

        model.setSource(PoolSource.ALL)

        assertEquals(PoolSource.ALL, model.state.value.config.source)
        assertNull(model.state.value.poolTotal)
        assertNull(model.state.value.error)
    }

    @Test
    fun abortingWhilePlayingStopsThePlayerClearsTheSavedGameAndReturnsToSetupWithoutScore() = runTest {
        val model = startedGame(rounds = 2, total = 5)
        model.selectGuess(model.currentSong())
        model.submitGuess()
        runCurrent()
        model.nextRound()
        runCurrent()
        assertEquals(Phase.PLAYING, model.state.value.phase)
        assertEquals(6, model.state.value.score)
        assertNotNull(repository.games[userId])
        val stopsBefore = player.stops.value

        model.abortGame()
        runCurrent()

        val state = model.state.value
        assertEquals(Phase.SETUP, state.phase)
        assertEquals(0, state.score)
        assertTrue(state.roundResults.isEmpty())
        assertNull(state.currentSong)
        assertNull(state.error)
        assertEquals(stopsBefore + 1, player.stops.value)
        assertEquals(1, repository.count(RepositoryCall.CLEAR_GAME))
        assertNull(repository.games[userId])
        assertEquals(0, repository.count(RepositoryCall.INSERT))
    }

    @Test
    fun aSavedGameOutsideTheRevealIsRestoredPlayingLoadedPausedAndSeeked() = runTest {
        val song = testSong(1)
        val results = listOf(
            RoundResult(randomPlatformUUID(), "First", "Artist", null, 1, true, 6),
            RoundResult(randomPlatformUUID(), "Second", "Artist", null, 4, true, 3)
        )
        val config = GameConfig(source = PoolSource.ALL, rounds = 5)
        repository.games[userId] = SavedGame(
            config = config,
            roundIndex = 2,
            attempt = 3,
            score = 9,
            roundResults = results,
            currentSongId = song.id,
            snippetOffsetMs = 12_000L,
            inReveal = false
        )
        coEvery { songService.byId(song.id) } returns song

        val model = createModel()

        val state = model.state.value
        assertEquals(Phase.PLAYING, state.phase)
        assertEquals(config, state.config)
        assertEquals(2, state.roundIndex)
        assertEquals(3, state.attempt)
        assertEquals(9, state.score)
        assertEquals(results, state.roundResults)
        assertEquals(song, state.currentSong)
        assertEquals(12_000L, state.snippetOffsetMs)
        assertNull(state.error)
        assertEquals(listOf(song.id to false), player.loads.value)
        assertEquals(1, player.seeks.value)
        assertEquals(12_000L, player.position.value)
        assertEquals(0, player.plays.value)
        assertFalse(player.playing.value)
        verify(exactly = 1) { playerModel.pause() }
    }

    @Test
    fun aSavedGameWhoseSongIsGoneReturnsToSetupWithTheErrorAndClearsTheSavedGame() = runTest {
        val missing = randomPlatformUUID()
        repository.games[userId] = SavedGame(
            config = GameConfig(rounds = 5),
            roundIndex = 1,
            attempt = 2,
            score = 6,
            roundResults = emptyList(),
            currentSongId = missing,
            snippetOffsetMs = 0L,
            inReveal = false
        )
        coEvery { songService.byId(missing) } returns null

        val model = createModel()

        assertEquals(Phase.SETUP, model.state.value.phase)
        assertEquals(SongGuessScreenModel.ERROR_NOT_ENOUGH_SONGS, model.state.value.error)
        assertNull(model.state.value.currentSong)
        assertEquals(1, repository.count(RepositoryCall.CLEAR_GAME))
        assertNull(repository.games[userId])
        assertTrue(player.loads.value.isEmpty())
    }

    @Test
    fun leaderboardNamesAreLookedUpOncePerId() = runTest {
        val playlist = playlist(1)
        val artist = Artist(randomPlatformUUID(), "Artist", false)
        repository.entries[userId] = listOf(leaderboardEntry(listOf(playlist.id), listOf(artist.id), 1L).copy(id = 100L))
        coEvery { userPlaylistService.byIds(listOf(playlist.id)) } returns listOf(playlist)
        coEvery { artistService.byIds(listOf(artist.id)) } returns listOf(artist)

        val model = createModel()
        assertEquals(mapOf(playlist.id to playlist.name), model.state.value.playlistNames)
        assertEquals(mapOf(artist.id to artist.name), model.state.value.artistNames)

        leaderboard.add(leaderboardEntry(listOf(playlist.id), listOf(artist.id), 2L))
        runCurrent()

        assertEquals(2, model.leaderboardEntries.value.size)
        coVerify(exactly = 1) { userPlaylistService.byIds(any()) }
        coVerify(exactly = 1) { artistService.byIds(any()) }
    }

    @Test
    fun aFailedNameLookupIsRetriedWithTheNextLeaderboardChange() = runTest {
        val playlist = playlist(1)
        val artist = Artist(randomPlatformUUID(), "Artist", false)
        repository.entries[userId] = listOf(leaderboardEntry(listOf(playlist.id), listOf(artist.id), 1L).copy(id = 100L))
        var lookups = 0
        coEvery { userPlaylistService.byIds(listOf(playlist.id)) } answers {
            if (lookups++ == 0) throw IOException("down")
            listOf(playlist)
        }
        coEvery { artistService.byIds(listOf(artist.id)) } returns listOf(artist)

        val model = createModel()
        assertTrue(model.state.value.playlistNames.isEmpty())
        assertTrue(model.state.value.artistNames.isEmpty())

        leaderboard.add(leaderboardEntry(listOf(playlist.id), listOf(artist.id), 2L))
        runCurrent()

        assertEquals(mapOf(playlist.id to playlist.name), model.state.value.playlistNames)
        assertEquals(mapOf(artist.id to artist.name), model.state.value.artistNames)
        coVerify(exactly = 2) { userPlaylistService.byIds(any()) }
        coVerify(exactly = 1) { artistService.byIds(any()) }
    }
}
