package dev.dertyp.synara.testing

import dev.dertyp.PlatformUUID
import dev.dertyp.synara.player.AudioLoadError
import dev.dertyp.synara.player.AudioLoadFailure
import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.StereoSpectrum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class FakeAudioPlayer : AudioPlayer {
    val playing = MutableStateFlow(false)
    val errors = MutableSharedFlow<AudioLoadFailure>(extraBufferCapacity = 8)
    val finished = MutableSharedFlow<PlatformUUID?>(extraBufferCapacity = 8)
    val transitions = MutableSharedFlow<PlatformUUID>(extraBufferCapacity = 8)
    val loads = MutableStateFlow<List<Pair<PlatformUUID, Boolean>>>(emptyList())
    val starts = MutableStateFlow<List<Long>>(emptyList())
    val stops = MutableStateFlow(0)
    val plays = MutableStateFlow(0)
    val seeks = MutableStateFlow(0)
    val position = MutableStateFlow(0L)
    val loading = MutableStateFlow(false)
    val trackDuration = MutableStateFlow(0L)
    val prepared = MutableStateFlow<PlatformUUID?>(null)
    val prepares = MutableStateFlow<List<PlatformUUID>>(emptyList())
    val clears = MutableStateFlow(0)
    var deferLoads = false
    var current: PlatformUUID? = null
    private var pendingPlay = false

    override val isPlaying: StateFlow<Boolean> = playing
    override val isLoading: StateFlow<Boolean> = loading
    override val currentPosition: StateFlow<Long> = position
    override val duration: StateFlow<Long> = trackDuration
    override val volume: StateFlow<Float> = MutableStateFlow(1f)
    override val onFinished: Flow<PlatformUUID?> = finished
    override val loadErrors: Flow<AudioLoadFailure> = errors
    override val trackTransitions: Flow<PlatformUUID> = transitions

    override val sampleRate: StateFlow<Int> = MutableStateFlow(0)
    override val bitsPerSample: StateFlow<Int> = MutableStateFlow(0)
    override val bitRate: StateFlow<Long> = MutableStateFlow(0L)
    override val fftData: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0))
    override val stereoFftData: StateFlow<StereoSpectrum> = MutableStateFlow(StereoSpectrum.EMPTY)

    override val availableOutputDevices: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val currentOutputDevice: StateFlow<String?> = MutableStateFlow(null)

    override fun setOutputDevice(deviceSpecifier: String?) = Unit

    override fun play() {
        plays.update { it + 1 }
        playing.value = true
        if (loading.value) pendingPlay = true
    }

    override fun pause() {
        playing.value = false
        pendingPlay = false
    }

    override fun stop() {
        stops.update { it + 1 }
        current = null
        pendingPlay = false
        loading.value = false
        playing.value = false
        position.value = 0
        prepared.value = null
    }

    override fun seekTo(positionMs: Long) {
        position.value = positionMs
        seeks.update { it + 1 }
    }

    fun fail(error: AudioLoadError = AudioLoadError.Unavailable) {
        val issued = loads.value.size
        errors.tryEmit(AudioLoadFailure(current, error))
        if (loads.value.size != issued) return
        loading.value = false
        playing.value = false
    }

    fun emitFinished() {
        val issued = loads.value.size
        finished.tryEmit(current)
        if (loads.value.size == issued) playing.value = false
    }

    fun transition() {
        val next = checkNotNull(prepared.value)
        prepared.value = null
        current = next
        position.value = 0
        transitions.tryEmit(next)
    }

    override fun setVolume(volume: Float) = Unit
    override fun setFadeGain(gain: Float) = Unit

    override fun load(songId: PlatformUUID, playImmediately: Boolean, startMs: Long) {
        position.value = startMs
        current = songId
        pendingPlay = playImmediately
        if (prepared.value == songId) prepared.value = null
        if (deferLoads) loading.value = true
        loads.update { it + (songId to playImmediately) }
        starts.update { it + startMs }
        if (!deferLoads) {
            playing.value = playImmediately
            loading.value = false
        }
    }

    fun finishLoad() {
        playing.value = pendingPlay
        loading.value = false
    }

    override fun prepareNext(songId: PlatformUUID) {
        prepared.value = songId
        prepares.update { it + songId }
    }

    override fun clearNext() {
        prepared.value = null
        clears.update { it + 1 }
    }

    override fun release() = Unit
}
