package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.PodcastPlayerError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakePodcastPlayer : PodcastPlayer {
    sealed interface Call {
        data class PlayEpisode(val episode: PodcastEpisode, val startMs: Long?) : Call
        data class PlayEpisodes(val episodes: List<PodcastEpisode>, val startIndex: Int) : Call
        data class PlayShow(val showId: PlatformUUID) : Call
        data class PlayNext(val episode: PodcastEpisode) : Call
        data class AddToQueue(val episodes: List<PodcastEpisode>) : Call
        data class RemoveFromQueue(val index: Int) : Call
        data class MoveInQueue(val from: Int, val to: Int) : Call
        data object ClearQueue : Call
        data class PlayAt(val index: Int) : Call
        data object TogglePlayPause : Call
        data object Play : Call
        data object Pause : Call
        data object Stop : Call
        data class SeekTo(val positionMs: Long) : Call
        data object SkipBack : Call
        data object SkipForward : Call
        data object SkipNext : Call
        data object SkipPrevious : Call
        data class SetSpeed(val speed: Float) : Call
        data class SetVolume(val volume: Float) : Call
        data class MarkPlayed(val episode: PodcastEpisode, val played: Boolean) : Call
    }

    val calls = mutableListOf<Call>()

    val episodes = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    val index = MutableStateFlow(-1)
    val episode = MutableStateFlow<PodcastEpisode?>(null)
    val playing = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playbackSpeed = MutableStateFlow(1f)
    val volumeLevel = MutableStateFlow(1f)
    val fft = MutableStateFlow(FloatArray(0))
    val stereoFft = MutableStateFlow(StereoSpectrum.EMPTY)
    val rate = MutableStateFlow(0)
    val content = MutableStateFlow(false)
    val playerErrors = MutableSharedFlow<PodcastPlayerError>(extraBufferCapacity = 8)

    override val queue: StateFlow<List<PodcastEpisode>> = episodes
    override val currentIndex: StateFlow<Int> = index
    override val currentEpisode: StateFlow<PodcastEpisode?> = episode
    override val isPlaying: StateFlow<Boolean> = playing
    override val isLoading: StateFlow<Boolean> = loading
    override val position: StateFlow<Long> = positionMs
    override val duration: StateFlow<Long> = durationMs
    override val speed: StateFlow<Float> = playbackSpeed
    override val volume: StateFlow<Float> = volumeLevel
    override val fftData: StateFlow<FloatArray> = fft
    override val stereoFftData: StateFlow<StereoSpectrum> = stereoFft
    override val sampleRate: StateFlow<Int> = rate
    override val hasContent: StateFlow<Boolean> = content
    override val errors: SharedFlow<PodcastPlayerError> = playerErrors

    override fun playEpisode(episode: PodcastEpisode, startMs: Long?) {
        calls += Call.PlayEpisode(episode, startMs)
    }

    override fun playEpisodes(episodes: List<PodcastEpisode>, startIndex: Int) {
        calls += Call.PlayEpisodes(episodes, startIndex)
    }

    override fun playShow(showId: PlatformUUID) {
        calls += Call.PlayShow(showId)
    }

    override fun playNext(episode: PodcastEpisode) {
        calls += Call.PlayNext(episode)
    }

    override fun addToQueue(episodes: List<PodcastEpisode>) {
        calls += Call.AddToQueue(episodes)
    }

    override fun removeFromQueue(index: Int) {
        calls += Call.RemoveFromQueue(index)
    }

    override fun moveInQueue(from: Int, to: Int) {
        calls += Call.MoveInQueue(from, to)
    }

    override fun clearQueue() {
        calls += Call.ClearQueue
    }

    override fun playAt(index: Int) {
        calls += Call.PlayAt(index)
    }

    override fun togglePlayPause() {
        calls += Call.TogglePlayPause
    }

    override fun play() {
        calls += Call.Play
    }

    override fun pause() {
        calls += Call.Pause
    }

    override fun stop() {
        calls += Call.Stop
    }

    override fun seekTo(positionMs: Long) {
        calls += Call.SeekTo(positionMs)
    }

    override fun skipBack() {
        calls += Call.SkipBack
    }

    override fun skipForward() {
        calls += Call.SkipForward
    }

    override fun skipNext() {
        calls += Call.SkipNext
    }

    override fun skipPrevious() {
        calls += Call.SkipPrevious
    }

    override fun setSpeed(speed: Float) {
        calls += Call.SetSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        calls += Call.SetVolume(volume)
    }

    override fun markPlayed(episode: PodcastEpisode, played: Boolean) {
        calls += Call.MarkPlayed(episode, played)
    }
}
