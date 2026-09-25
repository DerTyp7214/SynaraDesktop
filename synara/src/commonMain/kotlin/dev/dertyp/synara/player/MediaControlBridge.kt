package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.core.joinArtists
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.synara.Config
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
    dispatchers: SynaraDispatchers
) {
    companion object {
        const val PODCAST_MIN_RATE = 0.8
        const val PODCAST_MAX_RATE = 2.0
    }

    private val scope = CoroutineScope(dispatchers.default + SupervisorJob())

    val isPodcastMode: StateFlow<Boolean> = playerSwitcher.active
        .map { it == ActivePlayer.PODCAST }
        .stateIn(scope, SharingStarted.Eagerly, playerSwitcher.active.value == ActivePlayer.PODCAST)

    private val podcastMode: Boolean get() = playerSwitcher.active.value == ActivePlayer.PODCAST

    val metadata: StateFlow<NowPlayingMetadata?> = playerSwitcher.active
        .flatMapLatest { active ->
            if (active == ActivePlayer.PODCAST) {
                combine(podcastPlayer.currentEpisode, podcastPlayer.duration) { episode, duration ->
                    episode?.toMetadata(duration)
                }
            } else {
                combine(playerModel.currentSong, Config.showTitleTagsInText) { song, _ -> song?.toMetadata() }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentMetadata())

    val isPlaying: StateFlow<Boolean> = playerSwitcher.active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.isPlaying else playerModel.isPlaying }
        .stateIn(scope, SharingStarted.Eagerly, currentIsPlaying())

    val positionFlow: StateFlow<Long> = playerSwitcher.active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) podcastPlayer.position else playerModel.currentPosition }
        .stateIn(scope, SharingStarted.Eagerly, position())

    val volume: StateFlow<Float> = playerModel.volume

    val repeatMode: StateFlow<RepeatMode> = playerSwitcher.active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) flowOf(RepeatMode.OFF) else playerModel.repeatMode }
        .stateIn(scope, SharingStarted.Eagerly, if (podcastMode) RepeatMode.OFF else playerModel.repeatMode.value)

    val shuffleMode: StateFlow<Boolean> = playerSwitcher.active
        .flatMapLatest { if (it == ActivePlayer.PODCAST) flowOf(false) else playerModel.shuffleMode }
        .stateIn(scope, SharingStarted.Eagerly, !podcastMode && playerModel.shuffleMode.value)

    val rate: StateFlow<Double> = playerSwitcher.active
        .flatMapLatest { active ->
            if (active == ActivePlayer.PODCAST) podcastPlayer.speed.map { it.toDouble() } else flowOf(1.0)
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentRate())

    val minimumRate: Double get() = if (podcastMode) PODCAST_MIN_RATE else 1.0
    val maximumRate: Double get() = if (podcastMode) PODCAST_MAX_RATE else 1.0

    fun position(): Long =
        if (podcastMode) podcastPlayer.position.value else playerModel.currentPosition.value

    fun play() = if (podcastMode) podcastPlayer.play() else playerModel.play()

    fun pause() = if (podcastMode) podcastPlayer.pause() else playerModel.pause()

    fun togglePlayPause() = if (podcastMode) podcastPlayer.togglePlayPause() else playerModel.togglePlayPause()

    fun stop() = if (podcastMode) podcastPlayer.stop() else playerModel.stop()

    fun next() = if (podcastMode) podcastPlayer.skipForward() else playerModel.skipNext()

    fun previous() = if (podcastMode) podcastPlayer.skipBack() else playerModel.skipPrevious()

    fun seekTo(positionMs: Long) =
        if (podcastMode) podcastPlayer.seekTo(positionMs) else playerModel.seekTo(positionMs)

    fun seekBy(offsetMs: Long) = seekTo(position() + offsetMs)

    fun setVolume(volume: Float) = playerModel.setVolume(volume)

    fun setRate(rate: Double) {
        if (!podcastMode) return
        val target = PodcastPlayer.SPEEDS.minBy { abs(it - rate.toFloat()) }
        podcastPlayer.setSpeed(target)
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (podcastMode) return
        playerModel.setRepeatMode(mode)
    }

    fun setShuffle(enabled: Boolean) {
        if (podcastMode) return
        if (playerModel.shuffleMode.value != enabled) playerModel.toggleShuffle()
    }

    private fun currentMetadata(): NowPlayingMetadata? =
        if (podcastMode) podcastPlayer.currentEpisode.value?.toMetadata(podcastPlayer.duration.value)
        else playerModel.currentSong.value?.toMetadata()

    private fun currentIsPlaying(): Boolean =
        if (podcastMode) podcastPlayer.isPlaying.value else playerModel.isPlaying.value

    private fun currentRate(): Double =
        if (podcastMode) podcastPlayer.speed.value.toDouble() else 1.0

    private fun UserSong.toMetadata() = NowPlayingMetadata(
        trackId = id.toString(),
        title = textTitle(),
        artists = artists.map { it.name },
        artist = artists.joinArtists(),
        album = album?.name,
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
