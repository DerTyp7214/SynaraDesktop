package dev.dertyp.synara.player.audio.source

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.AudioSource
import dev.dertyp.synara.player.audio.FlowInputStream
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream

class SongAudioSource(
    val songId: PlatformUUID,
    private val quality: Int,
    private val songService: ISongService,
    private val songLookup: suspend (PlatformUUID) -> UserSong?
) : AudioSource {
    @Volatile
    var song: UserSong? = null
        private set

    override val cacheKey: String = "$songId:$quality"
    override val durationMsHint: Long? get() = song?.duration
    override val bitRateHint: Long? get() = song?.effectiveAudio?.bitRate
    override val formatHint: String? get() = song?.effectiveAudio?.codec

    override suspend fun prepare(): Boolean {
        if (song == null) song = songLookup(songId)
        return song != null
    }

    override suspend fun size(): Long {
        val knownSize = song?.effectiveAudio?.fileSize ?: 0L
        return if (quality == 0) {
            if (knownSize > 0) knownSize else songService.getStreamSize(songId)
        } else {
            val size = songService.getDownloadSize(songId, quality, force = false)
            if (size > 0) size
            else if (knownSize > 0) knownSize
            else songService.getStreamSize(songId)
        }
    }

    override suspend fun open(offset: Long, scope: CoroutineScope): InputStream? {
        val flow = if (quality == 0) {
            songService.streamSong(songId, offset)
        } else {
            songService.downloadSong(songId, quality, offset, force = false)
        } ?: return null
        return FlowInputStream(flow, scope)
    }
}
