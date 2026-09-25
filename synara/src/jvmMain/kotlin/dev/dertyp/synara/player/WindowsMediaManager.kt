package dev.dertyp.synara.player

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

class WindowsMediaManager(private val bridge: MediaControlBridge) : SystemMediaManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mediaSession: MediaSession? = null

    override fun start() {
        if (!OSUtils.isWindows) return

        mediaSession = MediaSession.create {
            bridge.position()
        }?.apply {
            onPlay = { bridge.play() }
            onPause = { bridge.pause() }
            onNext = { bridge.next() }
            onPrevious = { bridge.previous() }
            onSeek = { byMs: Long -> bridge.seekBy(byMs) }
            onSetPosition = { toMs: Long -> bridge.seekTo(toMs) }
            onPlayPause = { bridge.togglePlayPause() }
            onStop = { bridge.stop() }
            onSetLoop = { loopMode ->
                val targetMode = when (loopMode) {
                    MediaSessionLoopMode.NONE -> RepeatMode.OFF
                    MediaSessionLoopMode.ONE -> RepeatMode.ONE
                    MediaSessionLoopMode.ALL -> RepeatMode.ALL
                }
                bridge.setRepeatMode(targetMode)
            }
            onSetShuffle = { shuffleMode ->
                bridge.setShuffle(shuffleMode)
            }
            
            setEnabled(enabled = true)
        }

        scope.launch {
            bridge.isPlaying.collectLatest {
                mediaSession?.setPlaybackStatus(if (it) MediaSessionPlaybackStatus.PLAYING else MediaSessionPlaybackStatus.PAUSED)
            }
        }

        scope.launch {
            bridge.metadata.collectLatest { nowPlaying ->
                nowPlaying?.let {
                    val metadata = MediaSessionMetadata(
                        title = it.title,
                        artist = it.artist,
                        album = it.album ?: "",
                        length_ms = it.durationMs
                    )
                    mediaSession?.setMetadata(metadata)
                }
            }
        }

        scope.launch {
            bridge.repeatMode.collectLatest {
                val libMode = when (it) {
                    RepeatMode.OFF -> MediaSessionLoopMode.NONE
                    RepeatMode.ALL -> MediaSessionLoopMode.ALL
                    RepeatMode.ONE -> MediaSessionLoopMode.ONE
                }
                mediaSession?.setLoopMode(libMode)
            }
        }

        scope.launch {
            bridge.shuffleMode.collectLatest {
                mediaSession?.setShuffle(it)
            }
        }

        scope.launch {
            bridge.volume.collectLatest {
                mediaSession?.setVolume(it)
            }
        }
    }
}

