package dev.dertyp.synara.player

import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.StateFlow

interface PlaybackSurface {
    val isPlaying: StateFlow<Boolean>
    val currentSong: StateFlow<UserSong?>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val volume: StateFlow<Float>
    val shuffleMode: StateFlow<Boolean>
    val repeatMode: StateFlow<RepeatMode>
    val supportsVolume: Boolean

    fun togglePlayPause()
    fun skipNext()
    fun skipPrevious()
    fun seekTo(positionMs: Long)
    fun setVolume(value: Float)
    fun toggleShuffle()
    fun toggleRepeat()
}

class LocalPlaybackSurface(private val playerModel: PlayerModel) : PlaybackSurface {
    override val isPlaying: StateFlow<Boolean> get() = playerModel.isPlaying
    override val currentSong: StateFlow<UserSong?> get() = playerModel.currentSong
    override val currentPosition: StateFlow<Long> get() = playerModel.currentPosition
    override val duration: StateFlow<Long> get() = playerModel.duration
    override val volume: StateFlow<Float> get() = playerModel.volume
    override val shuffleMode: StateFlow<Boolean> get() = playerModel.shuffleMode
    override val repeatMode: StateFlow<RepeatMode> get() = playerModel.repeatMode
    override val supportsVolume: Boolean = true

    override fun togglePlayPause() = playerModel.togglePlayPause()

    override fun skipNext() = playerModel.skipNext()

    override fun skipPrevious() = playerModel.skipPrevious()

    override fun seekTo(positionMs: Long) = playerModel.seekTo(positionMs)

    override fun setVolume(value: Float) = playerModel.setVolume(value)

    override fun toggleShuffle() = playerModel.toggleShuffle()

    override fun toggleRepeat() = playerModel.toggleRepeat()
}
