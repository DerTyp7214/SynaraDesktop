package dev.dertyp.synara.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class StubDownloadManager : IDownloadManager {
    override val queue: StateFlow<List<UserSong>> = MutableStateFlow(emptyList())
    override val currentDownload: StateFlow<DownloadProgress?> = MutableStateFlow(null)

    override fun getDownloadStatus(songId: PlatformUUID): Flow<DownloadStatus> = flowOf(DownloadStatus.NotDownloaded)
    override fun isAlbumDownloaded(albumId: PlatformUUID): Flow<Boolean> = flowOf(false)
    override fun isArtistDownloaded(artistId: PlatformUUID): Flow<Boolean> = flowOf(false)
    override fun isPlaylistDownloaded(playlistId: PlatformUUID): Flow<Boolean> = flowOf(false)

    override fun downloadSong(songId: PlatformUUID, explicitlySaved: Boolean) {}
    override fun downloadAlbum(albumId: PlatformUUID, explicitlySaved: Boolean) {}
    override fun downloadArtist(artistId: PlatformUUID, explicitlySaved: Boolean) {}
    override fun downloadPlaylist(playlistId: PlatformUUID, explicitlySaved: Boolean) {}
    override fun downloadFavorites() {}
    override fun removeSong(id: PlatformUUID) {}
    override fun removeAlbum(id: PlatformUUID) {}
    override fun removeArtist(id: PlatformUUID) {}
    override fun removePlaylist(id: PlatformUUID) {}
}
