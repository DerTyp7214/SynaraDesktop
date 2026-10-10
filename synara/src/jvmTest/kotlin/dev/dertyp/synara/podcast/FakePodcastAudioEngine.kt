package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.synara.player.StereoSpectrum
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakePodcastAudioEngine : PodcastAudioEngine {
    data class Load(val episodeId: PlatformUUID, val startMs: Long, val play: Boolean)

    override val position: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val duration: MutableStateFlow<Long> = MutableStateFlow(0L)
    override val isPlaying: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val fftData: MutableStateFlow<FloatArray> = MutableStateFlow(FloatArray(0))
    override val stereoFftData: MutableStateFlow<StereoSpectrum> = MutableStateFlow(StereoSpectrum.EMPTY)
    override val sampleRate: MutableStateFlow<Int> = MutableStateFlow(0)

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    private val _errors = MutableSharedFlow<PodcastPlayerError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<PodcastPlayerError> = _errors.asSharedFlow()

    val loads = mutableListOf<Load>()
    val seeks = mutableListOf<Long>()
    val speeds = mutableListOf<Float>()
    val volumes = mutableListOf<Float>()
    val outputDevices = mutableListOf<String?>()
    var playCalls = 0
    var pauseCalls = 0
    var stopCalls = 0

    private var playRequested = false

    fun completeLoad() {
        check(isLoading.value) { "completeLoad without a pending load" }
        isPlaying.value = playRequested
        isLoading.value = false
    }

    fun emitFinished() {
        val loadsBefore = loads.size
        _finished.tryEmit(Unit)
        if (loads.size == loadsBefore) {
            isPlaying.value = false
            isLoading.value = false
        }
    }

    fun emitError(error: PodcastPlayerError) {
        _errors.tryEmit(error)
        isPlaying.value = false
        isLoading.value = false
    }

    override fun load(episode: PodcastEpisode, startMs: Long, play: Boolean) {
        loads += Load(episode.id, startMs, play)
        playRequested = play
        if (!play) isPlaying.value = false
        position.value = startMs
        isLoading.value = true
    }

    override fun play() {
        playCalls++
        playRequested = true
        isPlaying.value = true
    }

    override fun pause() {
        pauseCalls++
        playRequested = false
        isPlaying.value = false
    }

    override fun stop() {
        stopCalls++
        playRequested = false
        isLoading.value = false
        isPlaying.value = false
        position.value = 0L
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        position.value = positionMs
    }

    override fun setSpeed(speed: Float) {
        speeds += speed
    }

    override fun setVolume(volume: Float) {
        volumes += volume
    }

    override fun setOutputDevice(deviceSpecifier: String?) {
        outputDevices += deviceSpecifier
    }
}
