package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformInstant
import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.SongTag
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class SongServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ISongService {
    override suspend fun setLiked(id: PlatformUUID, liked: Boolean, addedAt: PlatformInstant?): UserSong? {
        return manager.getService<ISongService>().setLiked(id, liked, addedAt)
    }

    override suspend fun setLyrics(id: PlatformUUID, lyrics: List<String>): UserSong? {
        return manager.getService<ISongService>().setLyrics(id, lyrics)
    }

    override suspend fun setArtists(id: PlatformUUID, artistIds: List<PlatformUUID>): UserSong? {
        return manager.getService<ISongService>().setArtists(id, artistIds)
    }

    override suspend fun setMusicBrainzId(id: PlatformUUID, musicBrainzId: PlatformUUID?): UserSong? {
        return manager.getService<ISongService>().setMusicBrainzId(id, musicBrainzId)
    }

    override suspend fun fetchMusicBrainzId(id: PlatformUUID): UserSong? {
        return manager.getService<ISongService>().fetchMusicBrainzId(id)
    }

    override suspend fun byId(id: PlatformUUID): UserSong? {
        return manager.getService<ISongService>().byId(id)
    }

    override suspend fun byMusicBrainzId(musicBrainzId: PlatformUUID): List<UserSong> {
        return manager.getService<ISongService>().byMusicBrainzId(musicBrainzId)
    }

    override suspend fun byIds(ids: Collection<PlatformUUID>): List<UserSong> {
        return manager.getService<ISongService>().byIds(ids)
    }

    override suspend fun byTitle(page: Int, pageSize: Int, title: String): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().byTitle(page, pageSize, title)
    }

    override suspend fun byArtist(page: Int, pageSize: Int, artistId: PlatformUUID): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().byArtist(page, pageSize, artistId)
    }

    override suspend fun likedByArtist(
        page: Int,
        pageSize: Int,
        artistId: PlatformUUID,
        explicit: Boolean
    ): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().likedByArtist(page, pageSize, artistId, explicit)
    }

    override suspend fun byAlbum(page: Int, pageSize: Int, albumId: PlatformUUID): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().byAlbum(page, pageSize, albumId)
    }

    override suspend fun byPlaylist(page: Int, pageSize: Int, playlistId: PlatformUUID): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().byPlaylist(page, pageSize, playlistId)
    }

    override suspend fun byUserPlaylist(page: Int, pageSize: Int, playlistId: PlatformUUID): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().byUserPlaylist(page, pageSize, playlistId)
    }

    override suspend fun byTidalTrackIds(ids: Collection<String>): List<UserSong> {
        return manager.getService<ISongService>().byTidalTrackIds(ids)
    }

    override suspend fun byTidalTracks(tracks: Collection<IMetadataService.Track>): List<UserSong> {
        return manager.getService<ISongService>().byTidalTracks(tracks)
    }

    override suspend fun likedSongs(page: Int, pageSize: Int, explicit: Boolean): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().likedSongs(page, pageSize, explicit)
    }

    override suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        tags: List<SongTag>,
        invertTags: Boolean
    ): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().allSongs(page, pageSize, explicit, tags, invertTags)
    }

    override suspend fun deleteSongs(ids: Collection<PlatformUUID>): Boolean {
        return manager.getService<ISongService>().deleteSongs(ids)
    }

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        liked: Boolean
    ): PaginatedResponse<UserSong> {
        return manager.getService<ISongService>().rankedSearch(page, pageSize, query, explicit, liked)
    }

    override fun streamSong(id: PlatformUUID, offset: Long, chunkSize: Int): Flow<ByteArray>? {
        return manager.getService<ISongService>().streamSong(id, offset, chunkSize)
    }

    override fun downloadSong(id: PlatformUUID, quality: Int, offset: Long, chunkSize: Int): Flow<ByteArray>? {
        return manager.getService<ISongService>().downloadSong(id, quality, offset, chunkSize)
    }

    override suspend fun getStreamSize(id: PlatformUUID): Long {
        return manager.getService<ISongService>().getStreamSize(id)
    }

    override suspend fun getDownloadSize(id: PlatformUUID, quality: Int): Long {
        return manager.getService<ISongService>().getDownloadSize(id, quality)
    }

    override fun allSongIds(
        explicit: Boolean,
        tags: List<SongTag>,
        invertTags: Boolean
    ): Flow<PlatformUUID> {
        return manager.getService<ISongService>().allSongIds(explicit, tags, invertTags)
    }

    override fun likedSongIds(explicit: Boolean): Flow<PlatformUUID> {
        return manager.getService<ISongService>().likedSongIds(explicit)
    }

    override fun songIdsByArtist(artistId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ISongService>().songIdsByArtist(artistId)
    }

    override fun songIdsByAlbum(albumId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ISongService>().songIdsByAlbum(albumId)
    }

    override fun songIdsByPlaylist(playlistId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ISongService>().songIdsByPlaylist(playlistId)
    }

    override fun songIdsByUserPlaylist(playlistId: PlatformUUID): Flow<PlatformUUID> {
        return manager.getService<ISongService>().songIdsByUserPlaylist(playlistId)
    }
}
