package dev.dertyp.synara.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class DownloadProgress(
    val song: UserSong,
    val downloadedBytes: Long,
    val totalBytes: Long
)

enum class DownloadStatus {
    NotDownloaded,
    Queued,
    Downloading,
    Downloaded
}

interface IDownloadManager {
    val queue: StateFlow<List<UserSong>>
    val currentDownload: StateFlow<DownloadProgress?>

    fun getDownloadStatus(songId: PlatformUUID): Flow<DownloadStatus>
    fun isAlbumDownloaded(albumId: PlatformUUID): Flow<Boolean>
    fun isArtistDownloaded(artistId: PlatformUUID): Flow<Boolean>
    fun isPlaylistDownloaded(playlistId: PlatformUUID): Flow<Boolean>

    fun downloadSong(songId: PlatformUUID, explicitlySaved: Boolean = true)
    fun downloadAlbum(albumId: PlatformUUID, explicitlySaved: Boolean = true)
    fun downloadArtist(artistId: PlatformUUID, explicitlySaved: Boolean = true)
    fun downloadPlaylist(playlistId: PlatformUUID, explicitlySaved: Boolean = true)
    fun downloadFavorites()
    
    fun removeSong(id: PlatformUUID)
    fun removeAlbum(id: PlatformUUID)
    fun removeArtist(id: PlatformUUID)
    fun removePlaylist(id: PlatformUUID)
}
