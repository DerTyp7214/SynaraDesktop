package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.dertyp.PlatformUUID
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.joinArtists
import dev.dertyp.core.stripAccents
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.Artist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.game.GameAudioPlayer
import dev.dertyp.synara.game.GameConfig
import dev.dertyp.synara.game.LeaderboardEntry
import dev.dertyp.synara.game.MAX_ATTEMPTS
import dev.dertyp.synara.game.MAX_POINTS_PER_ROUND
import dev.dertyp.synara.game.MIN_SONG_DURATION_MS
import dev.dertyp.synara.game.PoolSource
import dev.dertyp.synara.game.RANDOM_OFFSET_TAIL_MS
import dev.dertyp.synara.game.RoundResult
import dev.dertyp.synara.game.SNIPPET_LADDER_MS
import dev.dertyp.synara.game.audioStartFloorMs
import dev.dertyp.synara.game.SavedGame
import dev.dertyp.synara.game.SnippetStart
import dev.dertyp.synara.game.SongGuessLeaderboard
import dev.dertyp.synara.game.pointsForAttempt
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class SongGuessScreenModel(
    private val songService: ISongService,
    private val artistService: IArtistService,
    private val userPlaylistService: IUserPlaylistService,
    private val globalState: GlobalStateModel,
    private val rpcServiceManager: RpcServiceManager,
    private val dispatchers: SynaraDispatchers,
    private val leaderboard: SongGuessLeaderboard,
    private val playerModel: PlayerModel,
    gameAudioPlayer: GameAudioPlayer,
) : StateScreenModel<SongGuessScreenModel.SongGuessState>(SongGuessState()) {

    private val player = gameAudioPlayer.player

    enum class Phase { SETUP, STARTING, PLAYING, REVEAL, FINISHED }

    data class SongGuessState(
        val phase: Phase = Phase.SETUP,
        val config: GameConfig = GameConfig(),
        val selectedArtists: List<Artist> = emptyList(),
        val artistQuery: String = "",
        val artistSearchResults: List<Artist> = emptyList(),
        val poolTotal: Int? = null,
        val error: String? = null,

        val roundIndex: Int = 0,
        val currentSong: UserSong? = null,
        val snippetOffsetMs: Long = 0L,
        val attempt: Int = 0,
        val guessQuery: String = "",
        val suggestions: List<UserSong> = emptyList(),
        val selectedGuess: UserSong? = null,
        val isSnippetPlaying: Boolean = false,
        val snippetProgress: Float = 0f,
        val lastGuessWrong: Boolean = false,
        val roundResults: List<RoundResult> = emptyList(),
        val score: Int = 0,
        val lastRoundPoints: Int = 0,
        val revealPosition: Long = 0L,
        val revealIsPlaying: Boolean = false,

        val playlistNames: Map<PlatformUUID, String> = emptyMap(),
        val artistNames: Map<PlatformUUID, String> = emptyMap(),
    )

    val leaderboardEntries: StateFlow<List<LeaderboardEntry>> = leaderboard.entries
    val userPlaylists: StateFlow<List<UserPlaylist>> = globalState.userPlaylists

    private var pool: SongPool? = null
    private var snippetJob: Job? = null
    private var revealJob: Job? = null
    private var guessSearchJob: Job? = null
    private var artistSearchJob: Job? = null
    private val resolvedIds = mutableSetOf<PlatformUUID>()

    init {
        screenModelScope.launch(dispatchers.io) {
            leaderboard.entries.collect { resolveNames(it) }
        }
        screenModelScope.launch(dispatchers.io) { restoreGame() }
    }

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        val s = state.value
        val song = s.currentSong ?: return
        if (s.phase != Phase.PLAYING && s.phase != Phase.REVEAL) return
        leaderboard.saveGame(
            SavedGame(
                config = s.config,
                roundIndex = s.roundIndex,
                attempt = s.attempt,
                score = s.score,
                roundResults = s.roundResults,
                currentSongId = song.id,
                snippetOffsetMs = s.snippetOffsetMs,
                inReveal = s.phase == Phase.REVEAL
            )
        )
    }

    private suspend fun restoreGame() {
        val saved = leaderboard.loadGame() ?: return
        if (state.value.phase != Phase.SETUP) return
        mutableState.update { it.copy(phase = Phase.STARTING, config = saved.config, error = null) }
        try {
            rpcServiceManager.awaitAuthentication()
            val song = songService.byId(saved.currentSongId)
            if (song == null) {
                leaderboard.clearGame()
                mutableState.update { it.copy(phase = Phase.SETUP, error = ERROR_NOT_ENOUGH_SONGS) }
                return
            }
            val used = saved.roundResults.map { it.songId }.toSet() + song.id
            val artists = if (saved.config.artistIds.isEmpty()) emptyList()
            else artistService.byIds(saved.config.artistIds)
            pool = SongPool(saved.config, used)
            playerModel.pause()
            player.setVolume(playerModel.volume.value)
            player.stop()
            player.load(song.id, playImmediately = false)
            if (saved.snippetOffsetMs > 0L) player.seekTo(saved.snippetOffsetMs)
            mutableState.update {
                it.copy(
                    phase = if (saved.inReveal) Phase.REVEAL else Phase.PLAYING,
                    selectedArtists = artists,
                    roundIndex = saved.roundIndex,
                    attempt = saved.attempt,
                    score = saved.score,
                    roundResults = saved.roundResults,
                    lastRoundPoints = saved.roundResults.lastOrNull()?.points ?: 0,
                    currentSong = song,
                    snippetOffsetMs = saved.snippetOffsetMs,
                    guessQuery = "", suggestions = emptyList(), selectedGuess = null, lastGuessWrong = false,
                    isSnippetPlaying = false, snippetProgress = 0f,
                    revealPosition = saved.snippetOffsetMs, revealIsPlaying = false
                )
            }
            if (saved.inReveal) startReveal()
        } catch (e: Exception) {
            mutableState.update { it.copy(phase = Phase.SETUP, error = e.message ?: "Unknown error") }
        }
    }

    // ---------------------------------------------------------------- setup

    fun setSource(source: PoolSource) = mutableState.update {
        it.copy(config = it.config.copy(source = source), poolTotal = null, error = null)
    }

    fun setRounds(rounds: Int) = mutableState.update { it.copy(config = it.config.copy(rounds = rounds)) }

    fun setSnippetStart(start: SnippetStart) =
        mutableState.update { it.copy(config = it.config.copy(snippetStart = start)) }

    fun togglePlaylist(id: PlatformUUID) = mutableState.update {
        val ids = it.config.playlistIds
        val newIds = if (id in ids) ids - id else ids + id
        it.copy(config = it.config.copy(playlistIds = newIds), poolTotal = null)
    }

    fun setAllPlaylists(select: Boolean) = mutableState.update {
        val newIds = if (select) globalState.userPlaylists.value.map { p -> p.id } else emptyList()
        it.copy(config = it.config.copy(playlistIds = newIds), poolTotal = null)
    }

    fun searchArtists(query: String) {
        artistSearchJob?.cancel()
        mutableState.update { it.copy(artistQuery = query) }
        if (query.length < 2) {
            mutableState.update { it.copy(artistSearchResults = emptyList()) }
            return
        }
        artistSearchJob = screenModelScope.launch(dispatchers.io) {
            delay(300.milliseconds)
            try {
                rpcServiceManager.awaitAuthentication()
                val results = artistService.rankedSearch(0, 10, query).data
                mutableState.update { it.copy(artistSearchResults = results) }
            } catch (_: Exception) {
            }
        }
    }

    fun addArtist(artist: Artist) = mutableState.update {
        if (it.selectedArtists.any { a -> a.id == artist.id }) return@update it
        it.copy(
            selectedArtists = it.selectedArtists + artist,
            config = it.config.copy(artistIds = it.config.artistIds + artist.id),
            artistQuery = "",
            artistSearchResults = emptyList(),
            poolTotal = null
        )
    }

    fun removeArtist(id: PlatformUUID) = mutableState.update {
        it.copy(
            selectedArtists = it.selectedArtists.filterNot { a -> a.id == id },
            config = it.config.copy(artistIds = it.config.artistIds - id),
            poolTotal = null
        )
    }

    // ---------------------------------------------------------------- game flow

    fun startGame() {
        val config = state.value.config
        if (config.source == PoolSource.PLAYLISTS && config.playlistIds.isEmpty()) return
        snippetJob?.cancel()
        mutableState.update {
            it.copy(
                phase = Phase.STARTING, error = null, roundIndex = 0, roundResults = emptyList(),
                score = 0, attempt = 0, currentSong = null, guessQuery = "", suggestions = emptyList(),
                selectedGuess = null, lastGuessWrong = false, isSnippetPlaying = false, snippetProgress = 0f
            )
        }
        screenModelScope.launch(dispatchers.io) {
            try {
                rpcServiceManager.awaitAuthentication()
                val newPool = SongPool(config)
                val total = newPool.total()
                mutableState.update { it.copy(poolTotal = total) }
                if (total < config.rounds) {
                    mutableState.update { it.copy(phase = Phase.SETUP, error = ERROR_NOT_ENOUGH_SONGS) }
                    return@launch
                }
                pool = newPool
                playerModel.pause()
                player.setVolume(playerModel.volume.value)
                startRound()
            } catch (e: Exception) {
                mutableState.update { it.copy(phase = Phase.SETUP, error = e.message ?: "Unknown error") }
            }
        }
    }

    private suspend fun startRound() {
        val config = state.value.config
        val song = pool?.draw()
        if (song == null) {
            finishGame(ERROR_NOT_ENOUGH_SONGS)
            return
        }
        val candidate = when (config.snippetStart) {
            SnippetStart.SONG_START -> 0L
            SnippetStart.RANDOM -> {
                val max = (song.duration - RANDOM_OFFSET_TAIL_MS).coerceAtLeast(0L)
                if (max <= 0L) 0L else Random.nextLong(0L, max)
            }
        }
        val offset = maxOf(candidate, audioStartFloorMs(song.audioStartMs))
        player.stop()
        player.load(song.id, playImmediately = false)
        if (offset > 0L) player.seekTo(offset)
        mutableState.update {
            it.copy(
                phase = Phase.PLAYING, currentSong = song, snippetOffsetMs = offset, attempt = 0,
                guessQuery = "", suggestions = emptyList(), selectedGuess = null, lastGuessWrong = false,
                isSnippetPlaying = false, snippetProgress = 0f
            )
        }
        persist()
    }

    fun playSnippet() {
        val s = state.value
        if (s.phase != Phase.PLAYING || s.currentSong == null) return
        snippetJob?.cancel()
        val offset = s.snippetOffsetMs
        val lengthMs = SNIPPET_LADDER_MS[s.attempt.coerceIn(0, MAX_ATTEMPTS - 1)]
        snippetJob = screenModelScope.launch(dispatchers.default) {
            mutableState.update { it.copy(isSnippetPlaying = true, snippetProgress = 0f) }
            if (player.currentPosition.value != offset) player.seekTo(offset)
            player.play()
            try {
                withTimeoutOrNull(lengthMs + SNIPPET_TIMEOUT_GRACE_MS) {
                    player.currentPosition.collect { pos ->
                        val played = (pos - offset).coerceAtLeast(0L)
                        mutableState.update { it.copy(snippetProgress = (played.toFloat() / lengthMs).coerceIn(0f, 1f)) }
                        if (played >= lengthMs) throw SnippetDone
                    }
                }
            } catch (_: SnippetDone) {
            } finally {
                player.pause()
                mutableState.update { it.copy(isSnippetPlaying = false, snippetProgress = 1f) }
            }
        }
    }

    fun stopSnippet() {
        snippetJob?.cancel()
        player.pause()
        mutableState.update { it.copy(isSnippetPlaying = false) }
    }

    fun updateGuess(query: String) {
        guessSearchJob?.cancel()
        mutableState.update { it.copy(guessQuery = query, selectedGuess = null, lastGuessWrong = false) }
        if (query.length < 2) {
            mutableState.update { it.copy(suggestions = emptyList()) }
            return
        }
        val liked = state.value.config.source == PoolSource.LIKED
        guessSearchJob = screenModelScope.launch(dispatchers.io) {
            delay(300.milliseconds)
            try {
                val songs = songService.rankedSearch(0, 8, query, explicit = true, liked = liked).data
                mutableState.update { it.copy(suggestions = songs) }
            } catch (_: Exception) {
            }
        }
    }

    fun selectGuess(song: UserSong) = mutableState.update {
        it.copy(selectedGuess = song, guessQuery = "${song.title} — ${song.artists.joinArtists()}", suggestions = emptyList())
    }

    fun submitGuess() {
        val s = state.value
        val guess = s.selectedGuess ?: return
        val song = s.currentSong ?: return
        if (matches(guess, song)) {
            endRound(solved = true)
        } else {
            wrongAttempt()
        }
    }

    private fun String.matchKey() = cleanTitle().stripAccents().lowercase().trim()

    private fun matches(guess: UserSong, song: UserSong): Boolean =
        guess.id == song.id ||
            (guess.title.matchKey() == song.title.matchKey() &&
                guess.artists.joinArtists().matchKey() == song.artists.joinArtists().matchKey())

    fun skip() {
        if (state.value.phase != Phase.PLAYING) return
        wrongAttempt()
    }

    private fun wrongAttempt() {
        stopSnippet()
        val next = state.value.attempt + 1
        if (next >= MAX_ATTEMPTS) {
            endRound(solved = false)
        } else {
            mutableState.update {
                it.copy(attempt = next, lastGuessWrong = true, guessQuery = "", selectedGuess = null, suggestions = emptyList())
            }
            persist()
        }
    }

    private fun endRound(solved: Boolean) {
        stopSnippet()
        val s = state.value
        val song = s.currentSong ?: return
        val points = if (solved) pointsForAttempt(s.attempt) else 0
        val result = RoundResult(
            songId = song.id,
            title = song.title,
            artist = song.artists.joinArtists(),
            coverId = song.coverId,
            attemptsUsed = s.attempt + 1,
            solved = solved,
            points = points
        )
        mutableState.update {
            it.copy(
                phase = Phase.REVEAL,
                roundResults = it.roundResults + result,
                score = it.score + points,
                lastRoundPoints = points,
                revealPosition = it.snippetOffsetMs,
                revealIsPlaying = false
            )
        }
        persist()
        startReveal()
    }

    // ---------------------------------------------------------------- reveal playback

    private fun startReveal() {
        revealJob?.cancel()
        revealJob = screenModelScope.launch(dispatchers.default) {
            player.play()
            launch { player.currentPosition.collect { pos -> mutableState.update { it.copy(revealPosition = pos) } } }
            launch { player.isPlaying.collect { playing -> mutableState.update { it.copy(revealIsPlaying = playing) } } }
        }
    }

    private fun stopReveal() {
        revealJob?.cancel()
        revealJob = null
        player.pause()
        mutableState.update { it.copy(revealIsPlaying = false, revealPosition = 0L) }
    }

    fun toggleRevealPlayback() {
        if (state.value.phase != Phase.REVEAL) return
        if (player.isPlaying.value) player.pause() else player.play()
    }

    fun seekReveal(positionMs: Long) {
        if (state.value.phase != Phase.REVEAL) return
        screenModelScope.launch(dispatchers.io) { player.seekTo(positionMs) }
    }

    fun nextRound() {
        val s = state.value
        if (s.phase != Phase.REVEAL) return
        stopReveal()
        if (s.roundIndex + 1 >= s.config.rounds) {
            screenModelScope.launch(dispatchers.io) { finishGame(null) }
        } else {
            mutableState.update { it.copy(roundIndex = it.roundIndex + 1) }
            screenModelScope.launch(dispatchers.io) {
                try {
                    startRound()
                } catch (e: Exception) {
                    finishGame(e.message)
                }
            }
        }
    }

    private fun finishGame(error: String?) {
        stopSnippet()
        stopReveal()
        player.stop()
        val s = state.value
        if (s.roundResults.isNotEmpty()) {
            leaderboard.add(
                LeaderboardEntry(
                    playedAt = currentTimeMillis(),
                    score = s.score,
                    maxScore = s.roundResults.size * MAX_POINTS_PER_ROUND,
                    config = s.config,
                    roundResults = s.roundResults
                )
            )
        }
        mutableState.update { it.copy(phase = Phase.FINISHED, currentSong = null, error = error) }
        leaderboard.clearGame()
    }

    fun abortGame() {
        stopSnippet()
        stopReveal()
        player.stop()
        pool = null
        leaderboard.clearGame()
        mutableState.update {
            it.copy(phase = Phase.SETUP, currentSong = null, roundResults = emptyList(), score = 0, error = null)
        }
    }

    fun backToSetup() {
        pool = null
        mutableState.update { it.copy(phase = Phase.SETUP, error = null) }
    }

    fun clearLeaderboard() = leaderboard.clear()

    // ---------------------------------------------------------------- name resolution

    private suspend fun resolveNames(entries: List<LeaderboardEntry>) {
        val playlistIds = entries.flatMap { it.config.playlistIds }.toSet() - resolvedIds
        val artistIds = entries.flatMap { it.config.artistIds }.toSet() - resolvedIds
        if (playlistIds.isEmpty() && artistIds.isEmpty()) return
        resolvedIds += playlistIds
        resolvedIds += artistIds
        try {
            rpcServiceManager.awaitAuthentication()
            val playlists = if (playlistIds.isEmpty()) emptyList() else userPlaylistService.byIds(playlistIds.toList())
            val artists = if (artistIds.isEmpty()) emptyList() else artistService.byIds(artistIds.toList())
            mutableState.update {
                it.copy(
                    playlistNames = it.playlistNames + playlists.associate { p -> p.id to p.name },
                    artistNames = it.artistNames + artists.associate { a -> a.id to a.name }
                )
            }
        } catch (_: Exception) {
            resolvedIds -= playlistIds
            resolvedIds -= artistIds
        }
    }

    override fun onDispose() {
        snippetJob?.cancel()
        revealJob?.cancel()
        player.stop()
        super.onDispose()
    }

    // ---------------------------------------------------------------- pool

    private inner class SongPool(private val config: GameConfig, initialUsed: Set<PlatformUUID> = emptySet()) {
        private val explicit = true
        private val used = initialUsed.toMutableSet()
        private val totals = mutableMapOf<PlatformUUID?, Int>()

        private val buckets: List<PlatformUUID?> = when {
            config.artistIds.isNotEmpty() -> config.artistIds
            config.source == PoolSource.PLAYLISTS -> config.playlistIds
            else -> listOf(null)
        }

        private suspend fun fetch(bucket: PlatformUUID?, page: Int) = when {
            bucket != null && config.artistIds.isNotEmpty() ->
                if (config.source == PoolSource.LIKED) songService.likedByArtist(page, 1, bucket, explicit)
                else songService.byArtist(page, 1, bucket)
            bucket != null -> songService.byUserPlaylist(page, 1, bucket)
            config.source == PoolSource.LIKED -> songService.likedSongs(page, 1, explicit)
            else -> songService.allSongs(page, 1, explicit)
        }

        private suspend fun countIn(bucket: PlatformUUID?): Int =
            totals.getOrPut(bucket) { fetch(bucket, 0).total }

        suspend fun total(): Int = buckets.sumOf { countIn(it) }

        suspend fun draw(): UserSong? {
            val candidates = buckets.filter { countIn(it) > 0 }
            if (candidates.isEmpty()) return null
            repeat(MAX_DRAW_TRIES) {
                val bucket = candidates.random()
                val idx = Random.nextInt(totals.getValue(bucket))
                val song = fetch(bucket, idx).data.firstOrNull() ?: return@repeat
                if (song.id in used || song.duration < MIN_SONG_DURATION_MS) return@repeat
                used += song.id
                return song
            }
            return null
        }
    }

    private object SnippetDone : RuntimeException() {
        private fun readResolve(): Any = SnippetDone
    }

    companion object {
        const val ERROR_NOT_ENOUGH_SONGS = "not_enough_songs"
        private const val MAX_DRAW_TRIES = 20
        private const val SNIPPET_TIMEOUT_GRACE_MS = 4_000L
    }
}
