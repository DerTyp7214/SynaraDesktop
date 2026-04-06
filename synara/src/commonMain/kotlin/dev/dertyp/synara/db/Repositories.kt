package dev.dertyp.synara.db

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import kotlinx.coroutines.flow.Flow

data class LocalHistoryEntry(
    val id: Long,
    val userId: PlatformUUID,
    val songId: PlatformUUID,
    val timestamp: Long,
    val song: UserSong
)

data class ScrobbleQueueEntry(
    val id: Long,
    val userId: PlatformUUID,
    val song: UserSong,
    val timestamp: Long,
    val target: String
)

interface RecentlyPlayedRepository {
    suspend fun insertSong(userId: PlatformUUID, song: UserSong, timestamp: Long)
    suspend fun insertAlbum(userId: PlatformUUID, album: Album, timestamp: Long)
    suspend fun insertArtist(userId: PlatformUUID, artist: Artist, timestamp: Long)
    
    suspend fun getSongs(userId: PlatformUUID, limit: Long): List<UserSong>
    suspend fun getAlbums(userId: PlatformUUID, limit: Long): List<Album>
    suspend fun getArtists(userId: PlatformUUID, limit: Long): List<Artist>

    fun getSongsFlow(userId: PlatformUUID, limit: Long): Flow<List<UserSong>>
    fun getAlbumsFlow(userId: PlatformUUID, limit: Long): Flow<List<Album>>
    fun getArtistsFlow(userId: PlatformUUID, limit: Long): Flow<List<Artist>>
    
    suspend fun deleteAllSongs(userId: PlatformUUID)
    suspend fun deleteAllAlbums(userId: PlatformUUID)
    suspend fun deleteAllArtists(userId: PlatformUUID)
}

interface UserRepository {
    suspend fun saveUser(user: User)
    suspend fun getUser(id: PlatformUUID): User?
}

interface ScrobbleQueueRepository {
    suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long, target: String)
    suspend fun getAll(userId: PlatformUUID, target: String): List<ScrobbleQueueEntry>
    suspend fun peek(userId: PlatformUUID, target: String): ScrobbleQueueEntry?
    suspend fun delete(id: Long)
    suspend fun getCount(userId: PlatformUUID, target: String): Long
}

interface LocalHistoryRepository {
    suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long)
    suspend fun get(userId: PlatformUUID, limit: Long): List<LocalHistoryEntry>
}

interface LibraryRepository {
    suspend fun saveSongMetadata(song: UserSong, explicitlySaved: Boolean)
    suspend fun saveAlbumMetadata(album: Album, explicitlySaved: Boolean)
    suspend fun saveArtistMetadata(artist: Artist, explicitlySaved: Boolean)
    suspend fun savePlaylistMetadata(playlist: UserPlaylist, explicitlySaved: Boolean)
    
    suspend fun addSongToPlaylist(playlistId: PlatformUUID, songId: PlatformUUID)
    suspend fun removeSongFromPlaylist(playlistId: PlatformUUID, songId: PlatformUUID)
    suspend fun getPlaylistSongs(playlistId: PlatformUUID): List<PlatformUUID>

    suspend fun getSongs(explicitlySavedOnly: Boolean = true): List<UserSong>
    suspend fun getAlbums(explicitlySavedOnly: Boolean): List<Album>
    suspend fun getArtists(explicitlySavedOnly: Boolean): List<Artist>
    suspend fun getPlaylists(explicitlySavedOnly: Boolean): List<UserPlaylist>

    suspend fun isSongSaved(id: PlatformUUID, explicitlySavedOnly: Boolean = true): Boolean
    suspend fun isAlbumSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean
    suspend fun isArtistSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean
    suspend fun isPlaylistSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean

    suspend fun deleteSong(id: PlatformUUID)
    suspend fun deleteAlbum(id: PlatformUUID)
    suspend fun deleteArtist(id: PlatformUUID)
    suspend fun deletePlaylist(id: PlatformUUID)
    
    suspend fun getSong(id: PlatformUUID): UserSong?
    suspend fun getAlbum(id: PlatformUUID): Album?
    suspend fun getArtist(id: PlatformUUID): Artist?
    suspend fun getPlaylist(id: PlatformUUID): UserPlaylist?

    fun observeChanges(): Flow<Unit>
}

interface DatabaseMigrationRepository {
    suspend fun migrateUserIds(newUserId: PlatformUUID)
}
