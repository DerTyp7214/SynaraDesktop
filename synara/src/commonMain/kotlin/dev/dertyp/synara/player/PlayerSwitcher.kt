package dev.dertyp.synara.player

import dev.dertyp.synara.Config
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ActivePlayer { SONGS, PODCAST }

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSwitcher(
    private val playerModel: PlayerModel,
    private val podcastPlayer: PodcastPlayer,
    dispatchers: SynaraDispatchers
) {
    private val scope = CoroutineScope(dispatchers.main + SupervisorJob())

    private val _active = MutableStateFlow(ActivePlayer.SONGS)
    val active: StateFlow<ActivePlayer> = _active.asStateFlow()

    val bothAvailable: StateFlow<Boolean> = combine(
        playerModel.currentSong.map { it != null },
        podcastPlayer.hasContent
    ) { songs, podcasts -> songs && podcasts }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val podcastIntensity: StateFlow<Float> = podcastPlayer.fftData.audioIntensityIn(scope)

    val fftData: StateFlow<FloatArray> = _active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.fftData else playerModel.fftData }
        .stateIn(scope, SharingStarted.Eagerly, FloatArray(0))

    val stereoFftData: StateFlow<StereoSpectrum> = _active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.stereoFftData else playerModel.stereoFftData }
        .stateIn(scope, SharingStarted.Eagerly, StereoSpectrum.EMPTY)

    val audioIntensity: StateFlow<Float> = _active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastIntensity else playerModel.audioIntensity }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    val isPlaying: StateFlow<Boolean> = _active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.isPlaying else playerModel.isPlaying }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val sampleRate: StateFlow<Int> = _active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.sampleRate else playerModel.sampleRate }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch {
            playerModel.isPlaying.filter { it }.collect {
                if (podcastPlayer.isPlaying.value) podcastPlayer.pause()
                _active.value = ActivePlayer.SONGS
            }
        }

        scope.launch {
            podcastPlayer.isPlaying.filter { it }.collect {
                if (playerModel.isPlaying.value) playerModel.pause()
                _active.value = ActivePlayer.PODCAST
            }
        }

        scope.launch {
            podcastPlayer.hasContent.filter { !it }.collect {
                if (_active.value == ActivePlayer.PODCAST) _active.value = ActivePlayer.SONGS
            }
        }

        scope.launch {
            Config.isPodcastsEnabled.filter { !it }.collect {
                podcastPlayer.stop()
                _active.value = ActivePlayer.SONGS
            }
        }
    }

    fun select(player: ActivePlayer) {
        if (player == ActivePlayer.PODCAST && !podcastPlayer.hasContent.value) return
        if (_active.value == player) return
        when (player) {
            ActivePlayer.SONGS -> if (podcastPlayer.isPlaying.value) podcastPlayer.pause()
            ActivePlayer.PODCAST -> if (playerModel.isPlaying.value) playerModel.pause()
        }
        _active.value = player
    }
}
