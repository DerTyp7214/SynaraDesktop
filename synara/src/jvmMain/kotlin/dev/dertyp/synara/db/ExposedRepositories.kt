package dev.dertyp.synara.db

import dev.dertyp.*
import dev.dertyp.data.*
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ExposedRecentlyPlayedRepository(
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val artistService: IArtistService,
    private val json: Json
) : RecentlyPlayedRepository {
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun insertSong(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        dbQuery {
            RecentlyPlayedSongs.upsert(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId) {
                it[RecentlyPlayedSongs.userId] = userId
                it[RecentlyPlayedSongs.songId] = song.id.toString()
                it[RecentlyPlayedSongs.timestamp] = timestamp
                it[RecentlyPlayedSongs.payload] = json.encodeToString(song)
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun insertAlbum(userId: PlatformUUID, album: Album, timestamp: Long) {
        dbQuery {
            RecentlyPlayedAlbums.upsert(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId) {
                it[RecentlyPlayedAlbums.userId] = userId
                it[RecentlyPlayedAlbums.albumId] = album.id.toString()
                it[RecentlyPlayedAlbums.timestamp] = timestamp
                it[RecentlyPlayedAlbums.payload] = json.encodeToString(album)
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun insertArtist(userId: PlatformUUID, artist: Artist, timestamp: Long) {
        dbQuery {
            RecentlyPlayedArtists.upsert(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId) {
                it[RecentlyPlayedArtists.userId] = userId
                it[RecentlyPlayedArtists.artistId] = artist.id.toString()
                it[RecentlyPlayedArtists.timestamp] = timestamp
                it[RecentlyPlayedArtists.payload] = json.encodeToString(artist)
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun getSongs(userId: PlatformUUID, limit: Long): List<UserSong> {
        return dbQuery {
            RecentlyPlayedSongs.selectAll()
                .where { RecentlyPlayedSongs.userId eq userId }
                .orderBy(RecentlyPlayedSongs.timestamp, SortOrder.DESC)
                .limit(limit.toInt())
                .map { json.decodeFromString<UserSong>(it[RecentlyPlayedSongs.payload]) }
        }
    }

    override suspend fun getAlbums(userId: PlatformUUID, limit: Long): List<Album> {
        return dbQuery {
            RecentlyPlayedAlbums.selectAll()
                .where { RecentlyPlayedAlbums.userId eq userId }
                .orderBy(RecentlyPlayedAlbums.timestamp, SortOrder.DESC)
                .limit(limit.toInt())
                .map { json.decodeFromString<Album>(it[RecentlyPlayedAlbums.payload]) }
        }
    }

    override suspend fun getArtists(userId: PlatformUUID, limit: Long): List<Artist> {
        return dbQuery {
            RecentlyPlayedArtists.selectAll()
                .where { RecentlyPlayedArtists.userId eq userId }
                .orderBy(RecentlyPlayedArtists.timestamp, SortOrder.DESC)
                .limit(limit.toInt())
                .map { json.decodeFromString<Artist>(it[RecentlyPlayedArtists.payload]) }
        }
    }

    override fun getSongsFlow(userId: PlatformUUID, limit: Long): Flow<List<UserSong>> {
        return _updates.asSharedFlow().onStart { emit(Unit) }.map {
            getSongs(userId, limit)
        }
    }

    override fun getAlbumsFlow(userId: PlatformUUID, limit: Long): Flow<List<Album>> {
        return _updates.asSharedFlow().onStart { emit(Unit) }.map {
            getAlbums(userId, limit)
        }
    }

    override fun getArtistsFlow(userId: PlatformUUID, limit: Long): Flow<List<Artist>> {
        return _updates.asSharedFlow().onStart { emit(Unit) }.map {
            getArtists(userId, limit)
        }
    }

    override suspend fun deleteAllSongs(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedSongs.deleteWhere { RecentlyPlayedSongs.userId eq userId }
        }
        _updates.emit(Unit)
    }

    override suspend fun deleteAllAlbums(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedAlbums.deleteWhere { RecentlyPlayedAlbums.userId eq userId }
        }
        _updates.emit(Unit)
    }

    override suspend fun deleteAllArtists(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedArtists.deleteWhere { RecentlyPlayedArtists.userId eq userId }
        }
        _updates.emit(Unit)
    }
}

class ExposedUserRepository : UserRepository {
    override suspend fun saveUser(user: User) {
        dbQuery {
            DownloadedUsers.upsert(DownloadedUsers.id) {
                it[id] = user.id
                it[username] = user.username
                it[displayName] = user.displayName
                it[passwordHash] = user.passwordHash
                it[isAdmin] = user.isAdmin
                it[profileImage] = user.profileImageId
            }
        }
    }

    override suspend fun getUser(id: PlatformUUID): User? {
        return dbQuery {
            DownloadedUsers.selectAll().where { DownloadedUsers.id eq id }
                .map { row ->
                    User(
                        id = row[DownloadedUsers.id].value,
                        username = row[DownloadedUsers.username],
                        displayName = row[DownloadedUsers.displayName],
                        passwordHash = row[DownloadedUsers.passwordHash],
                        isAdmin = row[DownloadedUsers.isAdmin],
                        profileImageId = row[DownloadedUsers.profileImage]?.value
                    )
                }
                .singleOrNull()
        }
    }
}

class ExposedScrobbleQueueRepository(private val json: Json) : ScrobbleQueueRepository {
    override suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long, target: String) {
        dbQuery {
            ScrobbleQueue.insert {
                it[ScrobbleQueue.userId] = userId
                it[ScrobbleQueue.songId] = song.id.toString()
                it[ScrobbleQueue.timestamp] = timestamp
                it[ScrobbleQueue.target] = target
                it[ScrobbleQueue.payload] = json.encodeToString(song)
            }
        }
    }

    override suspend fun getAll(userId: PlatformUUID, target: String): List<ScrobbleQueueEntry> {
        return dbQuery {
            ScrobbleQueue.selectAll()
                .where { (ScrobbleQueue.userId eq userId) and (ScrobbleQueue.target eq target) }
                .orderBy(ScrobbleQueue.timestamp, SortOrder.ASC)
                .map {
                    ScrobbleQueueEntry(
                        it[ScrobbleQueue.id].value.toLong(),
                        it[ScrobbleQueue.userId].value,
                        json.decodeFromString<UserSong>(it[ScrobbleQueue.payload]),
                        it[ScrobbleQueue.timestamp],
                        it[ScrobbleQueue.target]
                    )
                }
        }
    }

    override suspend fun peek(userId: PlatformUUID, target: String): ScrobbleQueueEntry? {
        return dbQuery {
            ScrobbleQueue.selectAll()
                .where { (ScrobbleQueue.userId eq userId) and (ScrobbleQueue.target eq target) }
                .orderBy(ScrobbleQueue.timestamp, SortOrder.ASC)
                .limit(1)
                .map {
                    ScrobbleQueueEntry(
                        it[ScrobbleQueue.id].value.toLong(),
                        it[ScrobbleQueue.userId].value,
                        json.decodeFromString<UserSong>(it[ScrobbleQueue.payload]),
                        it[ScrobbleQueue.timestamp],
                        it[ScrobbleQueue.target]
                    )
                }
                .singleOrNull()
        }
    }

    override suspend fun delete(id: Long) {
        dbQuery {
            ScrobbleQueue.deleteWhere { ScrobbleQueue.id eq id.toInt() }
        }
    }

    override suspend fun getCount(userId: PlatformUUID, target: String): Long {
        return dbQuery {
            ScrobbleQueue.selectAll()
                .where { (ScrobbleQueue.userId eq userId) and (ScrobbleQueue.target eq target) }
                .count()
        }
    }
}

class ExposedLocalHistoryRepository(private val json: Json) : LocalHistoryRepository {
    override suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        dbQuery {
            LocalHistory.insert {
                it[LocalHistory.userId] = userId
                it[LocalHistory.songId] = song.id.toString()
                it[LocalHistory.timestamp] = timestamp
                it[LocalHistory.payload] = json.encodeToString(song)
            }
        }
    }

    override suspend fun get(userId: PlatformUUID, limit: Long): List<LocalHistoryEntry> {
        return dbQuery {
            LocalHistory.selectAll()
                .where { LocalHistory.userId eq userId }
                .orderBy(LocalHistory.timestamp, SortOrder.DESC)
                .limit(limit.toInt())
                .map {
                    LocalHistoryEntry(
                        it[LocalHistory.id].value.toLong(),
                        it[LocalHistory.userId].value,
                        PlatformUUID.fromString(it[LocalHistory.songId]),
                        it[LocalHistory.timestamp],
                        json.decodeFromString<UserSong>(it[LocalHistory.payload])
                    )
                }
        }
    }
}

class ExposedLibraryRepository : LibraryRepository {
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun saveSongMetadata(song: UserSong, explicitlySaved: Boolean) {
        dbQuery {
            saveSongMetadataInternal(song, explicitlySaved)
        }
        _updates.emit(Unit)
    }

    override suspend fun saveAlbumMetadata(album: Album, explicitlySaved: Boolean) {
        dbQuery {
            saveAlbumMetadataInternal(album, explicitlySaved)
        }
        _updates.emit(Unit)
    }

    override suspend fun saveArtistMetadata(artist: Artist, explicitlySaved: Boolean) {
        dbQuery {
            saveArtistMetadataInternal(artist, explicitlySaved)
        }
        _updates.emit(Unit)
    }

    override suspend fun savePlaylistMetadata(playlist: UserPlaylist, explicitlySaved: Boolean) {
        dbQuery {
            DownloadedUserPlaylists.upsert(DownloadedUserPlaylists.id) {
                it[id] = playlist.id
                it[name] = playlist.name
                it[imageId] = playlist.imageId
                it[creator] = playlist.creator
                it[description] = playlist.description
                it[origin] = playlist.origin
                it[modifiedAt] = playlist.modifiedAt?.toEpochMilliseconds()
                if (explicitlySaved) it[DownloadedUserPlaylists.explicitlySaved] = true
            }
            DownloadedUserPlaylistSongs.deleteWhere { DownloadedUserPlaylistSongs.playlistId eq playlist.id }
            if (playlist.songEntries.isNullOrEmpty()) {
                playlist.songs.forEachIndexed { index, songId ->
                    DownloadedUserPlaylistSongs.insert {
                        it[playlistId] = playlist.id
                        it[this.songId] = songId
                        it[addedAt] = System.currentTimeMillis() + index
                    }
                }
            } else {
                playlist.songEntries!!.forEach { song ->
                    DownloadedUserPlaylistSongs.insert {
                        it[playlistId] = playlist.id
                        it[songId] = song.songId
                        it[addedAt] = song.addedAt
                        it[musicBrainzId] = song.musicBrainzId
                    }
                }
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun addSongToPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        dbQuery {
            DownloadedUserPlaylistSongs.insert {
                it[this.playlistId] = playlistId
                it[this.songId] = songId
                it[addedAt] = System.currentTimeMillis()
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun removeSongFromPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        dbQuery {
            DownloadedUserPlaylistSongs.deleteWhere {
                (DownloadedUserPlaylistSongs.playlistId eq playlistId) and (DownloadedUserPlaylistSongs.songId eq songId)
            }
        }
        _updates.emit(Unit)
    }

    override suspend fun getPlaylistSongs(playlistId: PlatformUUID): List<PlatformUUID> {
        return dbQuery {
            DownloadedUserPlaylistSongs.selectAll()
                .where { DownloadedUserPlaylistSongs.playlistId eq playlistId }
                .orderBy(DownloadedUserPlaylistSongs.addedAt, SortOrder.ASC)
                .map { it[DownloadedUserPlaylistSongs.songId].value }
        }
    }

    override suspend fun getSongs(explicitlySavedOnly: Boolean): List<UserSong> {
        return dbQuery {
            val query = DownloadedSongs.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedSongs.explicitlySaved eq true }
            }
            query.map { mapRowToUserSong(it) }
        }
    }

    override suspend fun getAlbums(explicitlySavedOnly: Boolean): List<Album> {
        return dbQuery {
            val query = DownloadedAlbums.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedAlbums.explicitlySaved eq true }
            }
            query.map { mapRowToAlbum(it) }
        }
    }

    override suspend fun getArtists(explicitlySavedOnly: Boolean): List<Artist> {
        return dbQuery {
            val query = DownloadedArtists.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedArtists.explicitlySaved eq true }
            }
            query.map { mapRowToArtist(it) }
        }
    }

    override suspend fun getPlaylists(explicitlySavedOnly: Boolean): List<UserPlaylist> {
        return dbQuery {
            val query = DownloadedUserPlaylists.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedUserPlaylists.explicitlySaved eq true }
            }
            query.map { mapRowToPlaylist(it) }
        }
    }

    override suspend fun isSongSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean {
        return dbQuery {
            val query = DownloadedSongs.selectAll().where { DownloadedSongs.id eq id }
            if (explicitlySavedOnly) {
                query.andWhere { DownloadedSongs.explicitlySaved eq true }
            }
            query.any()
        }
    }

    override suspend fun isAlbumSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean {
        return dbQuery {
            val query = DownloadedAlbums.selectAll().where { DownloadedAlbums.id eq id }
            if (explicitlySavedOnly) {
                query.andWhere { DownloadedAlbums.explicitlySaved eq true }
            }
            query.any()
        }
    }

    override suspend fun isArtistSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean {
        return dbQuery {
            val query = DownloadedArtists.selectAll().where { DownloadedArtists.id eq id }
            if (explicitlySavedOnly) {
                query.andWhere { DownloadedArtists.explicitlySaved eq true }
            }
            query.any()
        }
    }

    override suspend fun isPlaylistSaved(id: PlatformUUID, explicitlySavedOnly: Boolean): Boolean {
        return dbQuery {
            val query = DownloadedUserPlaylists.selectAll().where { DownloadedUserPlaylists.id eq id }
            if (explicitlySavedOnly) {
                query.andWhere { DownloadedUserPlaylists.explicitlySaved eq true }
            }
            query.any()
        }
    }

    override fun observeChanges(): Flow<Unit> = _updates.asSharedFlow()

    override suspend fun deleteSong(id: PlatformUUID) {
        dbQuery {
            DownloadedSongs.deleteWhere { DownloadedSongs.id eq id }
            DownloadedSongArtists.deleteWhere { DownloadedSongArtists.songId eq id }
            DownloadedUserPlaylistSongs.deleteWhere { DownloadedUserPlaylistSongs.songId eq id }
            DownloadedSongGenres.deleteWhere { DownloadedSongGenres.songId eq id }
        }
        _updates.emit(Unit)
    }

    override suspend fun deleteAlbum(id: PlatformUUID) {
        dbQuery {
            DownloadedAlbums.deleteWhere { DownloadedAlbums.id eq id }
            DownloadedAlbumArtists.deleteWhere { DownloadedAlbumArtists.albumId eq id }
            DownloadedAlbumGenres.deleteWhere { DownloadedAlbumGenres.albumId eq id }
        }
        _updates.emit(Unit)
    }

    override suspend fun deleteArtist(id: PlatformUUID) {
        dbQuery {
            DownloadedArtists.deleteWhere { DownloadedArtists.id eq id }
            DownloadedSongArtists.deleteWhere { DownloadedSongArtists.artistId eq id }
            DownloadedAlbumArtists.deleteWhere { DownloadedAlbumArtists.artistId eq id }
            DownloadedArtistMembers.deleteWhere { (DownloadedArtistMembers.groupId eq id) or (DownloadedArtistMembers.memberId eq id) }
            DownloadedArtistGenres.deleteWhere { DownloadedArtistGenres.artistId eq id }
        }
        _updates.emit(Unit)
    }

    override suspend fun deletePlaylist(id: PlatformUUID) {
        dbQuery {
            DownloadedUserPlaylists.deleteWhere { DownloadedUserPlaylists.id eq id }
            DownloadedUserPlaylistSongs.deleteWhere { DownloadedUserPlaylistSongs.playlistId eq id }
        }
        _updates.emit(Unit)
    }

    override suspend fun getSong(id: PlatformUUID): UserSong? {
        return dbQuery { getSongInternal(id) }
    }

    override suspend fun getAlbum(id: PlatformUUID): Album? {
        return dbQuery { getAlbumInternal(id) }
    }

    override suspend fun getArtist(id: PlatformUUID): Artist? {
        return dbQuery { getArtistInternal(id) }
    }

    override suspend fun getPlaylist(id: PlatformUUID): UserPlaylist? {
        return dbQuery { 
            DownloadedUserPlaylists.selectAll().where { DownloadedUserPlaylists.id eq id }
                .map { mapRowToPlaylist(it) }
                .singleOrNull()
        }
    }
}

class ExposedDatabaseMigrationRepository : DatabaseMigrationRepository {
    override suspend fun migrateUserIds(newUserId: PlatformUUID) {
        dbQuery {
            // Placeholder
        }
    }
}

// Internal mapping and saving functions

private fun getSongInternal(id: PlatformUUID): UserSong? {
    return DownloadedSongs.selectAll().where { DownloadedSongs.id eq id }
        .map { mapRowToUserSong(it) }
        .singleOrNull()
}

private fun getAlbumInternal(id: PlatformUUID): Album? {
    return DownloadedAlbums.selectAll().where { DownloadedAlbums.id eq id }
        .map { mapRowToAlbum(it) }
        .singleOrNull()
}

private fun getArtistInternal(id: PlatformUUID): Artist? {
    return DownloadedArtists.selectAll().where { DownloadedArtists.id eq id }
        .map { mapRowToArtist(it) }
        .singleOrNull()
}

private fun mapRowToUserSong(row: ResultRow): UserSong {
    val songId = row[DownloadedSongs.id].value
    val artists = DownloadedSongArtists.selectAll()
        .where { DownloadedSongArtists.songId eq songId }
        .map { getArtistInternal(it[DownloadedSongArtists.artistId].value)!! }
    
    val album = row[DownloadedSongs.albumId]?.let { getAlbumInternal(it.value) }
    
    val genres = DownloadedSongGenres.innerJoin(DownloadedGenres)
        .select(DownloadedGenres.id, DownloadedGenres.name)
        .where { DownloadedSongGenres.songId eq songId }
        .map { Genre(it[DownloadedGenres.id].value, it[DownloadedGenres.name]) }

    return UserSong(
        id = songId,
        title = row[DownloadedSongs.title],
        artists = artists,
        album = album,
        duration = row[DownloadedSongs.duration],
        explicit = row[DownloadedSongs.explicit],
        releaseDate = row[DownloadedSongs.releaseDate]?.toPlatformLocalDateISO(),
        lyrics = row[DownloadedSongs.lyrics],
        path = row[DownloadedSongs.filePath],
        originalUrl = row[DownloadedSongs.originalUrl],
        trackNumber = row[DownloadedSongs.trackNumber],
        discNumber = row[DownloadedSongs.discNumber],
        copyright = row[DownloadedSongs.copyright],
        sampleRate = row[DownloadedSongs.sampleRate],
        bitsPerSample = row[DownloadedSongs.bitsPerSample],
        bitRate = row[DownloadedSongs.bitRate],
        fileSize = row[DownloadedSongs.fileSize],
        coverId = row[DownloadedSongs.cover]?.value,
        musicBrainzId = row[DownloadedSongs.musicBrainzId],
        genres = genres,
        isFavourite = row[DownloadedSongs.isFavourite],
        userSongCreatedAt = row[DownloadedSongs.createdAt]?.let { platformDateFromEpochMilliseconds(it) },
        userSongUpdatedAt = row[DownloadedSongs.updatedAt]?.let { platformDateFromEpochMilliseconds(it) }
    )
}

private fun mapRowToAlbum(row: ResultRow): Album {
    val albumId = row[DownloadedAlbums.id].value
    val artists = DownloadedAlbumArtists.selectAll()
        .where { DownloadedAlbumArtists.albumId eq albumId }
        .map { getArtistInternal(it[DownloadedAlbumArtists.artistId].value)!! }
    
    val genres = DownloadedAlbumGenres.innerJoin(DownloadedGenres)
        .select(DownloadedGenres.id, DownloadedGenres.name)
        .where { DownloadedAlbumGenres.albumId eq albumId }
        .map { Genre(it[DownloadedGenres.id].value, it[DownloadedGenres.name]) }

    return Album(
        id = albumId,
        name = row[DownloadedAlbums.name],
        artists = artists,
        songCount = row[DownloadedAlbums.songCount],
        releaseDate = row[DownloadedAlbums.releaseDate]?.toPlatformLocalDateISO(),
        totalDuration = row[DownloadedAlbums.totalDuration],
        totalSize = row[DownloadedAlbums.totalSize],
        coverId = row[DownloadedAlbums.cover]?.value,
        originalId = row[DownloadedAlbums.originalId],
        musicbrainzId = row[DownloadedAlbums.musicBrainzId],
        genres = genres
    )
}

private fun mapRowToArtist(row: ResultRow): Artist {
    val artistId = row[DownloadedArtists.id].value
    
    val members = DownloadedArtistMembers.selectAll()
        .where { DownloadedArtistMembers.groupId eq artistId }
        .map { getArtistInternal(it[DownloadedArtistMembers.memberId].value)!! }
    
    val genres = DownloadedArtistGenres.innerJoin(DownloadedGenres)
        .select(DownloadedGenres.id, DownloadedGenres.name)
        .where { DownloadedArtistGenres.artistId eq artistId }
        .map { Genre(it[DownloadedGenres.id].value, it[DownloadedGenres.name]) }

    return Artist(
        id = artistId,
        name = row[DownloadedArtists.name],
        isGroup = row[DownloadedArtists.isGroup],
        artists = members,
        about = row[DownloadedArtists.about],
        imageId = row[DownloadedArtists.image]?.value,
        musicbrainzId = row[DownloadedArtists.musicBrainzId],
        isFollowed = row[DownloadedArtists.isFollowed],
        genres = genres
    )
}

private fun mapRowToPlaylist(row: ResultRow): UserPlaylist {
    val playlistId = row[DownloadedUserPlaylists.id].value
    val songs = DownloadedUserPlaylistSongs.selectAll()
        .where { DownloadedUserPlaylistSongs.playlistId eq playlistId }
        .orderBy(DownloadedUserPlaylistSongs.addedAt, SortOrder.ASC)
        .map { Triple(it[DownloadedUserPlaylistSongs.songId].value, it[DownloadedUserPlaylistSongs.addedAt], it[DownloadedUserPlaylistSongs.musicBrainzId]) }

    return UserPlaylist(
        id = playlistId,
        name = row[DownloadedUserPlaylists.name],
        songs = songs.map { it.first },
        songEntries = songs.map { UserPlaylistSong(it.first, it.second, it.third) },
        imageId = row[DownloadedUserPlaylists.imageId]?.value,
        creator = row[DownloadedUserPlaylists.creator].value,
        description = row[DownloadedUserPlaylists.description],
        origin = row[DownloadedUserPlaylists.origin],
        modifiedAt = row[DownloadedUserPlaylists.modifiedAt]?.let { platformDateFromEpochMilliseconds(it) },
    )
}

private fun saveSongMetadataInternal(song: UserSong, explicitlySaved: Boolean) {
    DownloadedSongs.upsert(DownloadedSongs.id) {
        it[id] = song.id
        it[title] = song.title
        it[albumId] = song.album?.id
        it[duration] = song.duration
        it[explicit] = song.explicit
        it[releaseDate] = song.releaseDate?.formatISO()
        it[lyrics] = song.lyrics
        it[filePath] = song.path
        it[originalUrl] = song.originalUrl
        it[trackNumber] = song.trackNumber
        it[discNumber] = song.discNumber
        it[copyright] = song.copyright
        it[sampleRate] = song.sampleRate
        it[bitsPerSample] = song.bitsPerSample
        it[bitRate] = song.bitRate
        it[fileSize] = song.fileSize
        it[cover] = song.coverId
        it[musicBrainzId] = song.musicBrainzId
        if (explicitlySaved) it[DownloadedSongs.explicitlySaved] = true
        it[isFavourite] = song.isFavourite ?: false
        it[createdAt] = song.userSongCreatedAt?.toEpochMilliseconds()
        it[updatedAt] = song.userSongUpdatedAt?.toEpochMilliseconds()
    }

    song.artists.forEach { artist ->
        saveArtistMetadataInternal(artist, false)
        DownloadedSongArtists.upsert(DownloadedSongArtists.songId, DownloadedSongArtists.artistId) {
            it[songId] = song.id
            it[artistId] = artist.id
        }
    }

    song.album?.let { saveAlbumMetadataInternal(it, false) }
    
    song.genres.forEach { genre ->
        val genreId = getOrCreateGenre(genre)
        DownloadedSongGenres.upsert(DownloadedSongGenres.songId, DownloadedSongGenres.genreId) {
            it[songId] = song.id
            it[this.genreId] = genreId
        }
    }
}

private fun saveAlbumMetadataInternal(album: Album, explicitlySaved: Boolean) {
    DownloadedAlbums.upsert(DownloadedAlbums.id) {
        it[id] = album.id
        it[name] = album.name
        it[songCount] = album.songCount
        it[releaseDate] = album.releaseDate?.formatISO()
        it[totalDuration] = album.totalDuration
        it[totalSize] = album.totalSize
        it[cover] = album.coverId
        it[originalId] = album.originalId
        it[musicBrainzId] = album.musicbrainzId
        if (explicitlySaved) it[DownloadedAlbums.explicitlySaved] = true
    }

    album.artists.forEach { artist ->
        saveArtistMetadataInternal(artist, false)
        DownloadedAlbumArtists.upsert(DownloadedAlbumArtists.albumId, DownloadedAlbumArtists.artistId) {
            it[albumId] = album.id
            it[artistId] = artist.id
        }
    }
    
    album.genres.forEach { genre ->
        val genreId = getOrCreateGenre(genre)
        DownloadedAlbumGenres.upsert(DownloadedAlbumGenres.albumId, DownloadedAlbumGenres.genreId) {
            it[albumId] = album.id
            it[this.genreId] = genreId
        }
    }
}

private fun saveArtistMetadataInternal(artist: Artist, explicitlySaved: Boolean) {
    DownloadedArtists.upsert(DownloadedArtists.id) {
        it[id] = artist.id
        it[name] = artist.name
        it[isGroup] = artist.isGroup
        it[about] = artist.about
        it[image] = artist.imageId
        it[musicBrainzId] = artist.musicbrainzId
        it[isFollowed] = artist.isFollowed
        if (explicitlySaved) it[DownloadedArtists.explicitlySaved] = true
    }
    
    artist.artists.forEach { member ->
        saveArtistMetadataInternal(member, false)
        DownloadedArtistMembers.upsert(DownloadedArtistMembers.groupId, DownloadedArtistMembers.memberId) {
            it[groupId] = artist.id
            it[memberId] = member.id
        }
    }
    
    artist.genres.forEach { genre ->
        val genreId = getOrCreateGenre(genre)
        DownloadedArtistGenres.upsert(DownloadedArtistGenres.artistId, DownloadedArtistGenres.genreId) {
            it[artistId] = artist.id
            it[this.genreId] = genreId
        }
    }
}

private fun getOrCreateGenre(genre: Genre): PlatformUUID {
    DownloadedGenres.upsert(DownloadedGenres.id) {
        it[id] = genre.id
        it[name] = genre.name
    }
    return genre.id
}
