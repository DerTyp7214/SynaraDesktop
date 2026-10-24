package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.AudioInfo
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

    private fun estimateSongSize(song: UserSong): Long =
        objectSize(34) +
            uuidSize * 2 +
            stringSize(song.title) + stringSize(song.path) + stringSize(song.originalUrl) + stringSize(song.copyright) +
            stringSize(song.blurHash) + stringSize(song.isrc) + stringSize(song.animatedCoverBlurHash) +
            stringSize(song.atmosVariantPath) +
            optionalSize(song.coverId) { uuidSize } + optionalSize(song.musicBrainzId) { uuidSize } +
            optionalSize(song.animatedCoverId) { uuidSize } + optionalSize(song.animatedCoverImageId) { uuidSize } +
            optionalSize(song.releaseDate) { dateSize } + optionalSize(song.userSongCreatedAt) { dateSize } +
            optionalSize(song.userSongUpdatedAt) { dateSize } + optionalSize(song.superLikedAt) { dateSize } +
            optionalSize(song.audioStartMs) { boxSize } + optionalSize(song.isFavourite) { boxSize } +
            optionalSize(song.audio) { estimateAudioSize(it) } + optionalSize(song.atmos) { estimateAudioSize(it) } +
            listSize(song.artists) { estimateArtistSize(it) } +
            optionalSize(song.album) { estimateAlbumSize(it) } +
            listSize(song.genres) { objectSize(2) + uuidSize + stringSize(it.name) } +
            listSize(song.tags) { objectSize(2) + stringSize(it.label) } +
            listSize(song.playbackTags) {
                objectSize(7) + uuidSize * 3 + stringSize(it.text) + optionalSize(it.endMs) { boxSize }
            } +
            optionalSize(song.lyricsInfo) {
                objectSize(7) + listSize(it.levels.toList()) { 0 } + stringSize(it.defaultProvider) +
                    optionalSize(it.defaultCandidateId) { uuidSize }
            }

    private fun estimateArtistSize(artist: ArtistCredit): Long =
        objectSize(11) +
            uuidSize +
            stringSize(artist.name) + stringSize(artist.blurHash) +
            stringSize(artist.creditedName) + stringSize(artist.joinPhrase) +
            optionalSize(artist.imageId) { uuidSize } + optionalSize(artist.musicBrainzId) { uuidSize } +
            listSize(artist.artists) { estimateArtistSize(it) } +
            listSize(artist.genres) { objectSize(2) + uuidSize + stringSize(it.name) }

    private fun estimateAlbumSize(album: Album): Long =
        objectSize(18) +
            uuidSize +
            stringSize(album.name) + stringSize(album.blurHash) + stringSize(album.originalId) +
            stringSize(album.barcode) + stringSize(album.animatedCoverBlurHash) +
            optionalSize(album.coverId) { uuidSize } + optionalSize(album.musicBrainzId) { uuidSize } +
            optionalSize(album.animatedCoverId) { uuidSize } + optionalSize(album.animatedCoverImageId) { uuidSize } +
            optionalSize(album.releaseDate) { dateSize } +
            listSize(album.artists) { estimateArtistSize(it) } +
            listSize(album.genres) { objectSize(2) + uuidSize + stringSize(it.name) } +
            listSize(album.tags) { objectSize(2) + stringSize(it.label) } +
            listSize(album.versions) { estimateAlbumSize(it) }

    private fun estimateAudioSize(audio: AudioInfo): Long = objectSize(4) + stringSize(audio.codec)

    private fun objectSize(fields: Int): Long = 16L + fields * 8L

    private fun stringSize(value: String?): Long = if (value == null) 0L else 40L + value.length * 2L

    private fun <T> listSize(items: List<T>, item: (T) -> Long): Long = 40L + items.size * 8L + items.sumOf(item)

    private fun <T : Any> optionalSize(value: T?, size: (T) -> Long): Long = if (value == null) 0L else size(value)

    private val uuidSize = 32L
    private val dateSize = 24L
    private val boxSize = 16L

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

    suspend fun refreshCached(songs: List<UserSong>) = mutex.withLock {
        songs.forEach { song ->
            val cached = cache[song.id] ?: return@forEach
            if (cached != song) {
                cache[song.id] = song
                _updates.tryEmit(CacheUpdate.SongUpdated(song))
            }
        }
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
