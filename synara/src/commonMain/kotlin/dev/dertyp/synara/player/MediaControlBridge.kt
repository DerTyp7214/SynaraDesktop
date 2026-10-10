package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.synara.ui.components.creditText
import dev.dertyp.synara.ui.components.creditedDisplayName
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.synara.Config
import dev.dertyp.synara.core.textName
import dev.dertyp.synara.core.textTitle
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.artworkId
import dev.dertyp.synara.podcast.knownDurationMs
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlin.math.abs

data class NowPlayingMetadata(
    val trackId: String,
    val title: String,
    val artists: List<String>,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val imageId: PlatformUUID?,
    val isPodcast: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControlBridge(
    private val playerModel: PlayerModel,
    private val podcastPlayer: PodcastPlayer,
    private val playerSwitcher: PlayerSwitcher,
    private val remotePlaybackController: RemotePlaybackController,
    dispatchers: SynaraDispatchers
) {
    companion object {
        const val PODCAST_MIN_RATE = 0.8
        const val PODCAST_MAX_RATE = 2.0
    }

    private enum class Source { SONGS, PODCAST, REMOTE }

    private val scope = CoroutineScope(dispatchers.default + SupervisorJob())

    val isPodcastMode: StateFlow<Boolean> = playerSwitcher.active
        .map { it == ActivePlayer.PODCAST }
        .stateIn(scope, SharingStarted.Eagerly, playerSwitcher.active.value == ActivePlayer.PODCAST)

    private val podcastMode: Boolean get() = playerSwitcher.active.value == ActivePlayer.PODCAST

    private fun sourceOf(active: ActivePlayer, controlled: Boolean): Source = when {
        active == ActivePlayer.PODCAST -> Source.PODCAST
        controlled -> Source.REMOTE
        else -> Source.SONGS
    }

    private fun currentSource(): Source = sourceOf(playerSwitcher.active.value, remotePlaybackController.target.value != null)

    private val source: StateFlow<Source> = combine(playerSwitcher.active, remotePlaybackController.target) { active, target ->
        sourceOf(active, target != null)
    }.stateIn(scope, SharingStarted.Eagerly, currentSource())

    val metadata: StateFlow<NowPlayingMetadata?> = source
        .flatMapLatest { current ->
            when (current) {
                Source.PODCAST -> combine(podcastPlayer.currentEpisode, podcastPlayer.duration) { episode, duration ->
                    episode?.toMetadata(duration)
                }
                Source.REMOTE -> combine(remotePlaybackController.currentSong, Config.showTitleTagsInText) { song, _ -> song?.toMetadata() }
                Source.SONGS -> combine(playerModel.currentSong, Config.showTitleTagsInText) { song, _ -> song?.toMetadata() }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentMetadata())

    val isPlaying: StateFlow<Boolean> = source
        .flatMapLatest {
            when (it) {
                Source.PODCAST -> podcastPlayer.isPlaying
                Source.REMOTE -> remotePlaybackController.isPlaying
                Source.SONGS -> playerModel.isPlaying
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, currentIsPlaying())

    val positionFlow: StateFlow<Long> = source
        .flatMapLatest {
            when (it) {
                Source.PODCAST -> podcastPlayer.position
                Source.REMOTE -> remotePlaybackController.currentPosition
                Source.SONGS -> playerModel.currentPosition
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, position())

    private fun volumeOnDevice(): Boolean = currentSource() == Source.REMOTE && remotePlaybackController.supportsVolume

    val volume: StateFlow<Float> = combine(playerSwitcher.active, remotePlaybackController.target) { _, _ -> volumeOnDevice() }
        .distinctUntilChanged()
        .flatMapLatest { onDevice -> if (onDevice) remotePlaybackController.volume else playerModel.volume }
        .stateIn(scope, SharingStarted.Eagerly, currentVolume())

    val repeatMode: StateFlow<RepeatMode> = source
        .flatMapLatest {
            when (it) {
                Source.PODCAST -> flowOf(RepeatMode.OFF)
                Source.REMOTE -> remotePlaybackController.repeatMode
                Source.SONGS -> playerModel.repeatMode
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, currentRepeatMode())

    val shuffleMode: StateFlow<Boolean> = source
        .flatMapLatest {
            when (it) {
                Source.PODCAST -> flowOf(false)
                Source.REMOTE -> remotePlaybackController.shuffleMode
                Source.SONGS -> playerModel.shuffleMode
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, currentShuffleMode())

    val rate: StateFlow<Double> = playerSwitcher.active
        .flatMapLatest { active ->
            if (active == ActivePlayer.PODCAST) podcastPlayer.speed.map { it.toDouble() } else flowOf(1.0)
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentRate())

    val minimumRate: Double get() = if (podcastMode) PODCAST_MIN_RATE else 1.0
    val maximumRate: Double get() = if (podcastMode) PODCAST_MAX_RATE else 1.0

    fun position(): Long = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.position.value
        Source.REMOTE -> remotePlaybackController.currentPosition.value
        Source.SONGS -> playerModel.currentPosition.value
    }

    fun play() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.play()
        Source.REMOTE -> remotePlaybackController.play()
        Source.SONGS -> playerModel.play()
    }

    fun pause() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.pause()
        Source.REMOTE -> remotePlaybackController.pause()
        Source.SONGS -> playerModel.pause()
    }

    fun togglePlayPause() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.togglePlayPause()
        Source.REMOTE -> remotePlaybackController.togglePlayPause()
        Source.SONGS -> playerModel.togglePlayPause()
    }

    fun stop() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.stop()
        Source.REMOTE -> remotePlaybackController.pause()
        Source.SONGS -> playerModel.stop()
    }

    fun next() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.skipForward()
        Source.REMOTE -> remotePlaybackController.skipNext()
        Source.SONGS -> playerModel.skipNext()
    }

    fun previous() = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.skipBack()
        Source.REMOTE -> remotePlaybackController.skipPrevious()
        Source.SONGS -> playerModel.skipPrevious()
    }

    fun seekTo(positionMs: Long) = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.seekTo(positionMs)
        Source.REMOTE -> remotePlaybackController.seekTo(positionMs)
        Source.SONGS -> playerModel.seekTo(positionMs)
    }

    fun seekBy(offsetMs: Long) = seekTo(position() + offsetMs)

    fun setVolume(volume: Float) {
        when (currentSource()) {
            Source.REMOTE -> if (remotePlaybackController.supportsVolume) remotePlaybackController.setVolume(volume)
            Source.PODCAST, Source.SONGS -> playerModel.setVolume(volume)
        }
    }

    fun setRate(rate: Double) {
        if (!podcastMode) return
        val target = PodcastPlayer.SPEEDS.minBy { abs(it - rate.toFloat()) }
        podcastPlayer.setSpeed(target)
    }

    fun setRepeatMode(mode: RepeatMode) {
        when (currentSource()) {
            Source.PODCAST -> Unit
            Source.REMOTE -> remotePlaybackController.setRepeatMode(mode)
            Source.SONGS -> playerModel.setRepeatMode(mode)
        }
    }

    fun setShuffle(enabled: Boolean) {
        when (currentSource()) {
            Source.PODCAST -> Unit
            Source.REMOTE -> if (remotePlaybackController.shuffleMode.value != enabled) remotePlaybackController.setShuffle(enabled)
            Source.SONGS -> if (playerModel.shuffleMode.value != enabled) playerModel.toggleShuffle()
        }
    }

    private fun currentMetadata(): NowPlayingMetadata? = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.currentEpisode.value?.toMetadata(podcastPlayer.duration.value)
        Source.REMOTE -> remotePlaybackController.currentSong.value?.toMetadata()
        Source.SONGS -> playerModel.currentSong.value?.toMetadata()
    }

    private fun currentIsPlaying(): Boolean = when (currentSource()) {
        Source.PODCAST -> podcastPlayer.isPlaying.value
        Source.REMOTE -> remotePlaybackController.isPlaying.value
        Source.SONGS -> playerModel.isPlaying.value
    }

    private fun currentRepeatMode(): RepeatMode = when (currentSource()) {
        Source.PODCAST -> RepeatMode.OFF
        Source.REMOTE -> remotePlaybackController.repeatMode.value
        Source.SONGS -> playerModel.repeatMode.value
    }

    private fun currentShuffleMode(): Boolean = when (currentSource()) {
        Source.PODCAST -> false
        Source.REMOTE -> remotePlaybackController.shuffleMode.value
        Source.SONGS -> playerModel.shuffleMode.value
    }

    private fun currentVolume(): Float =
        if (volumeOnDevice()) remotePlaybackController.volume.value else playerModel.volume.value

    private fun currentRate(): Double =
        if (podcastMode) podcastPlayer.speed.value.toDouble() else 1.0

    private fun UserSong.toMetadata() = NowPlayingMetadata(
        trackId = id.toString(),
        title = textTitle(),
        artists = artists.map { it.creditedDisplayName() },
        artist = artists.creditText(),
        album = album?.textName(),
        durationMs = duration,
        imageId = coverId,
        isPodcast = false
    )

    private fun PodcastEpisode.toMetadata(playerDuration: Long) = NowPlayingMetadata(
        trackId = id.toString(),
        title = title,
        artists = listOf(showTitle),
        artist = showTitle,
        album = showTitle,
        durationMs = playerDuration.takeIf { it > 0 } ?: knownDurationMs ?: 0L,
        imageId = artworkId,
        isPodcast = true
    )
}
