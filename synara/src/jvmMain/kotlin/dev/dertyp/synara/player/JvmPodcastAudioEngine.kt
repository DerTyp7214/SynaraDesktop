package dev.dertyp.synara.player

import dev.dertyp.data.PodcastEpisode
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.player.audio.AudioLoadError
import dev.dertyp.synara.player.audio.source.EpisodeAudioSource
import dev.dertyp.synara.podcast.PodcastAudioEngine
import dev.dertyp.synara.podcast.PodcastPlayerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class JvmPodcastAudioEngine(
    private val player: JvmAudioPlayer,
    private val podcastService: IPodcastService,
) : PodcastAudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val position: StateFlow<Long> = player.currentPosition
    override val duration: StateFlow<Long> = player.duration
    override val isPlaying: StateFlow<Boolean> = player.isPlaying
    override val isLoading: StateFlow<Boolean> = player.isLoading
    override val fftData: StateFlow<FloatArray> = player.fftData
    override val stereoFftData: StateFlow<StereoSpectrum> = player.stereoFftData
    override val sampleRate: StateFlow<Int> = player.sampleRate
    override val finished: SharedFlow<Unit> = player.onFinished

    private val _errors = MutableSharedFlow<PodcastPlayerError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<PodcastPlayerError> = _errors.asSharedFlow()

    init {
        player.timeStretchEnabled = true
        scope.launch {
            player.loadErrors.collect { error ->
                _errors.emit(
                    when (error) {
                        AudioLoadError.UnsupportedFormat -> PodcastPlayerError.UnsupportedFormat
                        AudioLoadError.Unavailable -> PodcastPlayerError.Unavailable
                        is AudioLoadError.Failed -> PodcastPlayerError.Failed(error.message)
                    }
                )
            }
        }
    }

    override fun load(episode: PodcastEpisode, startMs: Long, play: Boolean) {
        player.loadSource(EpisodeAudioSource(episode, podcastService), startMs, play)
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0L))

    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun setVolume(volume: Float) = player.setVolume(volume)

    override fun setOutputDevice(deviceSpecifier: String?) = player.setOutputDevice(deviceSpecifier)
}
