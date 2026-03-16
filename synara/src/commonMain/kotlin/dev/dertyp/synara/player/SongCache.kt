package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SongCache {
    private val maxSize: Int = 10000
    private val cache = LinkedHashMap<PlatformUUID, UserSong>()
    private val mutex = Mutex()
    
    val size: Int get() = cache.size

    fun getEstimatedMemoryUsage(): Long {
        var totalSize = 0L
        cache.values.forEach { song ->
            totalSize += estimateSongSize(song)
        }
        return totalSize
    }

    private fun estimateSongSize(song: UserSong): Long {
        var size = 128L // Fixed size fields and overhead

        size += (song.title.length * 2) + 24
        size += (song.lyrics.length * 2) + 24
        size += (song.path.length * 2) + 24
        size += (song.originalUrl.length * 2) + 24
        size += (song.copyright.length * 2) + 24

        size += 24L // List overhead
        song.artists.forEach { size += estimateArtistSize(it) }

        song.album?.let { size += estimateAlbumSize(it) } ?: run { size += 8 }

        return size
    }

    private fun estimateArtistSize(artist: Artist): Long {
        var size = 48L
        size += (artist.name.length * 2) + 24
        size += (artist.about.length * 2) + 24
        return size
    }

    private fun estimateAlbumSize(album: Album): Long {
        var size = 64L
        size += (album.name.length * 2) + 24
        size += 24L // List overhead
        album.artists.forEach { size += estimateArtistSize(it) }
        return size
    }

    private val _updates = MutableSharedFlow<CacheUpdate>(extraBufferCapacity = 64)
    val updates: SharedFlow<CacheUpdate> = _updates.asSharedFlow()

    private val _playlistUpdates = MutableSharedFlow<PlaylistUpdate>(extraBufferCapacity = 64)
    val playlistUpdates: SharedFlow<PlaylistUpdate> = _playlistUpdates.asSharedFlow()

    suspend fun get(id: PlatformUUID): UserSong? = mutex.withLock {
        val song = cache.remove(id)
        if (song != null) {
            cache[id] = song
        }
        song
    }

    suspend fun put(song: UserSong) = mutex.withLock {
        cache.remove(song.id)
        cache[song.id] = song
        _updates.tryEmit(CacheUpdate.SongUpdated(song))
        
        checkSize()
    }

    suspend fun putAll(songs: List<UserSong>) = mutex.withLock {
        songs.forEach { song ->
            cache.remove(song.id)
            cache[song.id] = song
            _updates.tryEmit(CacheUpdate.SongUpdated(song))
        }
        checkSize()
    }

    fun notifyPlaylistChanged(playlistId: PlatformUUID) {
        _playlistUpdates.tryEmit(PlaylistUpdate.PlaylistContentChanged(playlistId))
    }

    fun notifyPlaylistsChanged() {
        _playlistUpdates.tryEmit(PlaylistUpdate.PlaylistsReloadRequired)
    }

    fun notifyLikedSongsChanged() {
        _playlistUpdates.tryEmit(PlaylistUpdate.LikedSongsReloadRequired)
    }

    private fun checkSize() {
        while (cache.size > maxSize) {
            val it = cache.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else break
        }
    }
}

sealed class CacheUpdate {
    data class SongUpdated(val song: UserSong) : CacheUpdate()
}

sealed class PlaylistUpdate {
    data class PlaylistContentChanged(val playlistId: PlatformUUID) : PlaylistUpdate()
    data object PlaylistsReloadRequired : PlaylistUpdate()
    data object LikedSongsReloadRequired : PlaylistUpdate()
}
