package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class PodcastPlayerError {
    data object UnsupportedFormat : PodcastPlayerError()
    data object Unavailable : PodcastPlayerError()
    data class Failed(val message: String?) : PodcastPlayerError()
}

interface PodcastPlayer {
    val queue: StateFlow<List<PodcastEpisode>>
    val currentIndex: StateFlow<Int>
    val currentEpisode: StateFlow<PodcastEpisode?>
    val isPlaying: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    val position: StateFlow<Long>
    val duration: StateFlow<Long>
    val speed: StateFlow<Float>
    val volume: StateFlow<Float>
    val fftData: StateFlow<FloatArray>
    val sampleRate: StateFlow<Int>
    val hasContent: StateFlow<Boolean>
    val errors: SharedFlow<PodcastPlayerError>

    fun playEpisode(episode: PodcastEpisode, startMs: Long? = null)
    fun playEpisodes(episodes: List<PodcastEpisode>, startIndex: Int = 0)
    fun playShow(showId: PlatformUUID)
    fun playNext(episode: PodcastEpisode)
    fun addToQueue(episodes: List<PodcastEpisode>)
    fun removeFromQueue(index: Int)
    fun moveInQueue(from: Int, to: Int)
    fun clearQueue()
    fun playAt(index: Int)

    fun togglePlayPause()
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun skipBack()
    fun skipForward()
    fun skipNext()
    fun skipPrevious()
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun markPlayed(episode: PodcastEpisode, played: Boolean)

    companion object {
        val SPEEDS: List<Float> = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        const val SKIP_BACK_MS: Long = 15_000
        const val SKIP_FORWARD_MS: Long = 30_000
    }
}
