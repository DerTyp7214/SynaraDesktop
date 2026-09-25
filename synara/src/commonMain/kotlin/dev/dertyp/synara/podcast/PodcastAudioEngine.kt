package dev.dertyp.synara.podcast

import dev.dertyp.data.PodcastEpisode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

const val PODCAST_AUDIO_PLAYER = "podcast_audio_player"

interface PodcastAudioEngine {
    val position: StateFlow<Long>
    val duration: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    val fftData: StateFlow<FloatArray>
    val finished: SharedFlow<Unit>
    val errors: SharedFlow<PodcastPlayerError>

    fun load(episode: PodcastEpisode, startMs: Long, play: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)
    fun setOutputDevice(deviceSpecifier: String?)
}
