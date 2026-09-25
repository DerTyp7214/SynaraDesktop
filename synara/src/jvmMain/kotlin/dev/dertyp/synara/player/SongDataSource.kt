package dev.dertyp.synara.player

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.audio.source.SongAudioSource
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get

class SongDataSource(
    private val songService: ISongService,
    private val songCache: SongCache,
    private val settings: Settings
) {
    suspend fun getSong(songId: PlatformUUID): UserSong? {
        return songCache.get(songId) ?: songService.byId(songId)?.also { song ->
            songCache.put(song)
        }
    }

    fun source(songId: PlatformUUID): SongAudioSource =
        SongAudioSource(
            songId = songId,
            quality = settings.get(SettingKey.StreamingQuality, 0),
            songService = songService,
            songLookup = ::getSong
        )
}
