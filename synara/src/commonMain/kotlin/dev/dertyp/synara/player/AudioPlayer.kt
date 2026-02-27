package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AudioPlayer {
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val volume: StateFlow<Float>
    val onFinished: Flow<Unit>

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun load(songId: PlatformUUID)
    fun release()
}
