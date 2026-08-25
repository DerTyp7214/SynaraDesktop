@file:UseContextualSerialization(PlatformUUID::class)

package dev.dertyp.synara.game

import dev.dertyp.PlatformUUID
import dev.dertyp.synara.player.AudioPlayer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

const val GAME_AUDIO_PLAYER = "game_audio_player"

class GameAudioPlayer(val player: AudioPlayer)

val SNIPPET_LADDER_MS: List<Long> = listOf(100L, 500L, 1_000L, 2_000L, 4_000L, 8_000L)
val MAX_ATTEMPTS: Int get() = SNIPPET_LADDER_MS.size
val MAX_POINTS_PER_ROUND: Int get() = MAX_ATTEMPTS

fun pointsForAttempt(attempt: Int): Int = (MAX_ATTEMPTS - attempt).coerceAtLeast(0)

val ROUND_OPTIONS: List<Int> = listOf(5, 10, 15, 20)

const val MIN_SONG_DURATION_MS = 10_000L

const val RANDOM_OFFSET_TAIL_MS = 30_000L

@Serializable
enum class PoolSource { ALL, LIKED, PLAYLISTS }

@Serializable
enum class SnippetStart { SONG_START, RANDOM }

@Serializable
data class GameConfig(
    val source: PoolSource = PoolSource.LIKED,
    val playlistIds: List<PlatformUUID> = emptyList(),
    val artistIds: List<PlatformUUID> = emptyList(),
    val rounds: Int = 10,
    val snippetStart: SnippetStart = SnippetStart.SONG_START,
)

@Serializable
data class RoundResult(
    val songId: PlatformUUID,
    val title: String,
    val artist: String,
    val coverId: PlatformUUID? = null,
    val attemptsUsed: Int,
    val solved: Boolean,
    val points: Int,
)

data class LeaderboardEntry(
    val id: Long = 0L,
    val playedAt: Long,
    val score: Int,
    val maxScore: Int,
    val config: GameConfig,
    val roundResults: List<RoundResult>,
)
