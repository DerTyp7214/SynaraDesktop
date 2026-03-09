package dev.dertyp.synara.player

import dev.dertyp.core.joinArtists
import dev.dertyp.data.RepeatMode
import dev.dertyp.synara.utils.OSUtils
import dev.toastbits.mediasession.MediaSession
import dev.toastbits.mediasession.MediaSessionLoopMode
import dev.toastbits.mediasession.MediaSessionMetadata
import dev.toastbits.mediasession.MediaSessionPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WindowsMediaManager(private val playerModel: PlayerModel) : SystemMediaManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaSession: MediaSession? = null

    override fun start() {
        if (!OSUtils.isWindows) return

        mediaSession = MediaSession.create {
            playerModel.currentPosition.value
        }?.apply {
            onPlay = { playerModel.play() }
            onPause = { playerModel.pause() }
            onNext = { playerModel.skipNext() }
            onPrevious = { playerModel.skipPrevious() }
            onSeek = { byMs: Long -> playerModel.seekTo(playerModel.currentPosition.value + byMs) }
            onSetPosition = { toMs: Long -> playerModel.seekTo(toMs) }
            onPlayPause = { playerModel.togglePlayPause() }
            onStop = { playerModel.stop() }
            onSetLoop = { loopMode ->
                val targetMode = when (loopMode) {
                    MediaSessionLoopMode.NONE -> RepeatMode.OFF
                    MediaSessionLoopMode.ONE -> RepeatMode.ONE
                    MediaSessionLoopMode.ALL -> RepeatMode.ALL
                }
                playerModel.setRepeatMode(targetMode)
            }
            onSetShuffle = { shuffleMode ->
                if (playerModel.shuffleMode.value != shuffleMode) playerModel.toggleShuffle()
            }
            
            setEnabled(enabled = true)
        }

        scope.launch {
            playerModel.isPlaying.collectLatest {
                mediaSession?.setPlaybackStatus(if (it) MediaSessionPlaybackStatus.PLAYING else MediaSessionPlaybackStatus.PAUSED)
            }
        }

        scope.launch {
            playerModel.currentSong.collectLatest { song ->
                song?.let {
                    val metadata = MediaSessionMetadata(
                        title = it.title,
                        artist = it.artists.joinArtists(),
                        album = it.album?.name ?: "",
                        length_ms = it.duration
                    )
                    mediaSession?.setMetadata(metadata)
                }
            }
        }

        scope.launch {
            playerModel.repeatMode.collectLatest {
                val libMode = when (it) {
                    RepeatMode.OFF -> MediaSessionLoopMode.NONE
                    RepeatMode.ALL -> MediaSessionLoopMode.ALL
                    RepeatMode.ONE -> MediaSessionLoopMode.ONE
                }
                mediaSession?.setLoopMode(libMode)
            }
        }

        scope.launch {
            playerModel.shuffleMode.collectLatest {
                mediaSession?.setShuffle(it)
            }
        }

        scope.launch {
            playerModel.volume.collectLatest {
                mediaSession?.setVolume(it)
            }
        }
    }
}

