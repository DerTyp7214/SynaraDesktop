package dev.dertyp.synara.db

import dev.dertyp.*
import dev.dertyp.data.*
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.game.LeaderboardEntry
import dev.dertyp.synara.game.SavedGame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

private const val SQL_CHUNK_SIZE = 500

private val titleTagsJson = Json { ignoreUnknownKeys = true }

private fun decodeTitleTags(raw: String): List<TitleTag> {
    if (raw.isBlank()) return emptyList()
    return try {
        titleTagsJson.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun encodeTitleTags(tags: List<TitleTag>): String = titleTagsJson.encodeToString(tags)

@OptIn(ExperimentalUuidApi::class)
class ExposedRecentlyPlayedRepository(
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val artistService: IArtistService,
    private val json: Json
) : RecentlyPlayedRepository {
    private val _updates =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun upsertSong(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        RecentlyPlayedSongs.upsert(RecentlyPlayedSongs.userId, RecentlyPlayedSongs.songId) {
            it[RecentlyPlayedSongs.userId] = userId
            it[RecentlyPlayedSongs.songId] = song.id.toString()
            it[RecentlyPlayedSongs.timestamp] = timestamp
            it[RecentlyPlayedSongs.payload] = json.encodeToString(song)
        }
    }

    private fun upsertAlbum(userId: PlatformUUID, album: Album, timestamp: Long) {
        RecentlyPlayedAlbums.upsert(RecentlyPlayedAlbums.userId, RecentlyPlayedAlbums.albumId) {
            it[RecentlyPlayedAlbums.userId] = userId
            it[RecentlyPlayedAlbums.albumId] = album.id.toString()
            it[RecentlyPlayedAlbums.timestamp] = timestamp
            it[RecentlyPlayedAlbums.payload] = json.encodeToString(album)
        }
    }

    private fun upsertArtist(userId: PlatformUUID, artist: Artist, timestamp: Long) {
        RecentlyPlayedArtists.upsert(RecentlyPlayedArtists.userId, RecentlyPlayedArtists.artistId) {
            it[RecentlyPlayedArtists.userId] = userId
            it[RecentlyPlayedArtists.artistId] = artist.id.toString()
            it[RecentlyPlayedArtists.timestamp] = timestamp
            it[RecentlyPlayedArtists.payload] = json.encodeToString(artist)
        }
    }

    override suspend fun insertSong(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        dbQuery { upsertSong(userId, song, timestamp) }
        _updates.tryEmit(Unit)
    }

    override suspend fun insertAlbum(userId: PlatformUUID, album: Album, timestamp: Long) {
        dbQuery { upsertAlbum(userId, album, timestamp) }
        _updates.tryEmit(Unit)
    }

    override suspend fun insertArtist(userId: PlatformUUID, artist: Artist, timestamp: Long) {
        dbQuery { upsertArtist(userId, artist, timestamp) }
        _updates.tryEmit(Unit)
    }

    override suspend fun insertListen(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        dbQuery {
            upsertSong(userId, song, timestamp)
            song.album?.let { upsertAlbum(userId, it, timestamp) }
            song.artists.forEach { upsertArtist(userId, it, timestamp) }
        }
        _updates.tryEmit(Unit)
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
        return _updates.asSharedFlow().onStart { emit(Unit) }.conflate().map {
            getSongs(userId, limit)
        }
    }

    override fun getAlbumsFlow(userId: PlatformUUID, limit: Long): Flow<List<Album>> {
        return _updates.asSharedFlow().onStart { emit(Unit) }.conflate().map {
            getAlbums(userId, limit)
        }
    }

    override fun getArtistsFlow(userId: PlatformUUID, limit: Long): Flow<List<Artist>> {
        return _updates.asSharedFlow().onStart { emit(Unit) }.conflate().map {
            getArtists(userId, limit)
        }
    }

    override suspend fun deleteAllSongs(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedSongs.deleteWhere { RecentlyPlayedSongs.userId eq userId }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun deleteAllAlbums(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedAlbums.deleteWhere { RecentlyPlayedAlbums.userId eq userId }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun deleteAllArtists(userId: PlatformUUID) {
        dbQuery {
            RecentlyPlayedArtists.deleteWhere { RecentlyPlayedArtists.userId eq userId }
        }
        _updates.tryEmit(Unit)
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

    override suspend fun insertRaw(
        userId: PlatformUUID,
        songId: String,
        payload: String,
        timestamp: Long,
        target: String
    ) {
        dbQuery {
            ScrobbleQueue.insert {
                it[ScrobbleQueue.userId] = userId
                it[ScrobbleQueue.songId] = songId
                it[ScrobbleQueue.timestamp] = timestamp
                it[ScrobbleQueue.target] = target
                it[ScrobbleQueue.payload] = payload
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

    override suspend fun getAllRaw(userId: PlatformUUID, target: String): List<RawScrobbleQueueEntry> {
        return dbQuery {
            ScrobbleQueue.selectAll()
                .where { (ScrobbleQueue.userId eq userId) and (ScrobbleQueue.target eq target) }
                .orderBy(ScrobbleQueue.timestamp, SortOrder.ASC)
                .map {
                    RawScrobbleQueueEntry(
                        it[ScrobbleQueue.id].value.toLong(),
                        it[ScrobbleQueue.userId].value,
                        it[ScrobbleQueue.payload],
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
    private val _updates =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun saveSongMetadata(song: UserSong, explicitlySaved: Boolean) {
        dbQuery {
            saveSongMetadataInternal(song, explicitlySaved)
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun saveAlbumMetadata(album: Album, explicitlySaved: Boolean) {
        dbQuery {
            saveAlbumMetadataInternal(album, explicitlySaved)
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun saveArtistMetadata(artist: Artist, explicitlySaved: Boolean) {
        dbQuery {
            saveArtistMetadataInternal(artist, explicitlySaved)
        }
        _updates.tryEmit(Unit)
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
        _updates.tryEmit(Unit)
    }

    override suspend fun addSongToPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        addSongsToPlaylist(playlistId, listOf(songId))
    }

    override suspend fun addSongsToPlaylist(playlistId: PlatformUUID, songIds: List<PlatformUUID>) {
        if (songIds.isEmpty()) return
        dbQuery {
            val existing = DownloadedUserPlaylistSongs
                .select(DownloadedUserPlaylistSongs.songId)
                .where { DownloadedUserPlaylistSongs.playlistId eq playlistId }
                .mapTo(HashSet()) { it[DownloadedUserPlaylistSongs.songId].value }
            val now = System.currentTimeMillis()
            songIds.distinct()
                .filterNot { it in existing }
                .forEachIndexed { index, id ->
                    DownloadedUserPlaylistSongs.insert {
                        it[this.playlistId] = playlistId
                        it[this.songId] = id
                        it[addedAt] = now + index
                    }
                }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun removeSongFromPlaylist(playlistId: PlatformUUID, songId: PlatformUUID) {
        dbQuery {
            DownloadedUserPlaylistSongs.deleteWhere {
                (DownloadedUserPlaylistSongs.playlistId eq playlistId) and (DownloadedUserPlaylistSongs.songId eq songId)
            }
        }
        _updates.tryEmit(Unit)
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
            mapSongs(query.toList())
        }
    }

    override suspend fun getAlbums(explicitlySavedOnly: Boolean): List<Album> {
        return dbQuery {
            val query = DownloadedAlbums.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedAlbums.explicitlySaved eq true }
            }
            val ids = query.map { it[DownloadedAlbums.id].value }
            val albums = loadAlbums(ids)
            ids.mapNotNull { albums[it] }
        }
    }

    override suspend fun getArtists(explicitlySavedOnly: Boolean): List<Artist> {
        return dbQuery {
            val query = DownloadedArtists.selectAll()
            if (explicitlySavedOnly) {
                query.where { DownloadedArtists.explicitlySaved eq true }
            }
            val ids = query.map { it[DownloadedArtists.id].value }
            val artists = loadArtists(ids)
            ids.mapNotNull { artists[it] }
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

    override suspend fun getExplicitlySavedIds(): SavedLibraryIds {
        return dbQuery {
            SavedLibraryIds(
                songs = DownloadedSongs.select(DownloadedSongs.id)
                    .where { DownloadedSongs.explicitlySaved eq true }
                    .mapTo(HashSet()) { it[DownloadedSongs.id].value },
                albums = DownloadedAlbums.select(DownloadedAlbums.id)
                    .where { DownloadedAlbums.explicitlySaved eq true }
                    .mapTo(HashSet()) { it[DownloadedAlbums.id].value },
                artists = DownloadedArtists.select(DownloadedArtists.id)
                    .where { DownloadedArtists.explicitlySaved eq true }
                    .mapTo(HashSet()) { it[DownloadedArtists.id].value },
                playlists = DownloadedUserPlaylists.select(DownloadedUserPlaylists.id)
                    .where { DownloadedUserPlaylists.explicitlySaved eq true }
                    .mapTo(HashSet()) { it[DownloadedUserPlaylists.id].value }
            )
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
        _updates.tryEmit(Unit)
    }

    override suspend fun deleteAlbum(id: PlatformUUID) {
        dbQuery {
            DownloadedAlbums.deleteWhere { DownloadedAlbums.id eq id }
            DownloadedAlbumArtists.deleteWhere { DownloadedAlbumArtists.albumId eq id }
            DownloadedAlbumGenres.deleteWhere { DownloadedAlbumGenres.albumId eq id }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun deleteArtist(id: PlatformUUID) {
        dbQuery {
            DownloadedArtists.deleteWhere { DownloadedArtists.id eq id }
            DownloadedSongArtists.deleteWhere { DownloadedSongArtists.artistId eq id }
            DownloadedAlbumArtists.deleteWhere { DownloadedAlbumArtists.artistId eq id }
            DownloadedArtistMembers.deleteWhere { (DownloadedArtistMembers.groupId eq id) or (DownloadedArtistMembers.memberId eq id) }
            DownloadedArtistGenres.deleteWhere { DownloadedArtistGenres.artistId eq id }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun deletePlaylist(id: PlatformUUID) {
        dbQuery {
            DownloadedUserPlaylists.deleteWhere { DownloadedUserPlaylists.id eq id }
            DownloadedUserPlaylistSongs.deleteWhere { DownloadedUserPlaylistSongs.playlistId eq id }
        }
        _updates.tryEmit(Unit)
    }

    override suspend fun getSong(id: PlatformUUID): UserSong? {
        return dbQuery { getSongsInternal(listOf(id)).singleOrNull() }
    }

    override suspend fun getAlbum(id: PlatformUUID): Album? {
        return dbQuery { loadAlbums(listOf(id))[id] }
    }

    override suspend fun getArtist(id: PlatformUUID): Artist? {
        return dbQuery { loadArtists(listOf(id))[id] }
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

private fun loadGenres(
    link: Table,
    ownerCol: Column<EntityID<UUID>>,
    ids: Collection<UUID>
): Map<UUID, List<Genre>> {
    if (ids.isEmpty()) return emptyMap()
    val result = HashMap<UUID, MutableList<Genre>>()
    ids.distinct().chunked(SQL_CHUNK_SIZE).forEach { chunk ->
        link.innerJoin(DownloadedGenres)
            .select(ownerCol, DownloadedGenres.id, DownloadedGenres.name)
            .where { ownerCol inList chunk }
            .forEach { row ->
                result.getOrPut(row[ownerCol].value) { mutableListOf() }
                    .add(Genre(row[DownloadedGenres.id].value, row[DownloadedGenres.name]))
            }
    }
    return result
}

private fun loadArtists(ids: Collection<UUID>): Map<UUID, Artist> {
    if (ids.isEmpty()) return emptyMap()

    val visited = HashSet<UUID>(ids)
    val membersByGroup = HashMap<UUID, MutableList<UUID>>()
    var frontier: List<UUID> = visited.toList()
    while (frontier.isNotEmpty()) {
        val discovered = ArrayList<UUID>()
        frontier.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
            DownloadedArtistMembers
                .select(DownloadedArtistMembers.groupId, DownloadedArtistMembers.memberId)
                .where { DownloadedArtistMembers.groupId inList chunk }
                .forEach { row ->
                    val groupId = row[DownloadedArtistMembers.groupId].value
                    val memberId = row[DownloadedArtistMembers.memberId].value
                    membersByGroup.getOrPut(groupId) { mutableListOf() }.add(memberId)
                    if (visited.add(memberId)) discovered.add(memberId)
                }
        }
        frontier = discovered
    }

    val rows = HashMap<UUID, ResultRow>()
    visited.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
        DownloadedArtists.selectAll()
            .where { DownloadedArtists.id inList chunk }
            .forEach { rows[it[DownloadedArtists.id].value] = it }
    }

    val genres = loadGenres(DownloadedArtistGenres, DownloadedArtistGenres.artistId, rows.keys)

    val built = HashMap<UUID, Artist?>()
    val building = HashSet<UUID>()
    fun build(id: UUID): Artist? {
        if (built.containsKey(id)) return built[id]
        if (!building.add(id)) return null
        val artist = rows[id]?.let { row ->
            Artist(
                id = id,
                name = row[DownloadedArtists.name],
                isGroup = row[DownloadedArtists.isGroup],
                artists = membersByGroup[id].orEmpty().mapNotNull { build(it) },
                about = row[DownloadedArtists.about],
                imageId = row[DownloadedArtists.image]?.value,
                musicbrainzId = row[DownloadedArtists.musicBrainzId],
                isFollowed = row[DownloadedArtists.isFollowed],
                genres = genres[id].orEmpty()
            )
        }
        building.remove(id)
        built[id] = artist
        return artist
    }

    val result = HashMap<UUID, Artist>()
    ids.distinct().forEach { id -> build(id)?.let { result[id] = it } }
    return result
}

private fun loadAlbums(ids: Collection<UUID>): Map<UUID, Album> {
    if (ids.isEmpty()) return emptyMap()

    val rows = LinkedHashMap<UUID, ResultRow>()
    ids.distinct().chunked(SQL_CHUNK_SIZE).forEach { chunk ->
        DownloadedAlbums.selectAll()
            .where { DownloadedAlbums.id inList chunk }
            .forEach { rows[it[DownloadedAlbums.id].value] = it }
    }
    if (rows.isEmpty()) return emptyMap()

    val artistIdsByAlbum = HashMap<UUID, MutableList<UUID>>()
    rows.keys.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
        DownloadedAlbumArtists
            .select(DownloadedAlbumArtists.albumId, DownloadedAlbumArtists.artistId)
            .where { DownloadedAlbumArtists.albumId inList chunk }
            .forEach { row ->
                artistIdsByAlbum.getOrPut(row[DownloadedAlbumArtists.albumId].value) { mutableListOf() }
                    .add(row[DownloadedAlbumArtists.artistId].value)
            }
    }

    val artists = loadArtists(artistIdsByAlbum.values.flatten())
    val genres = loadGenres(DownloadedAlbumGenres, DownloadedAlbumGenres.albumId, rows.keys)

    return rows.mapValues { (albumId, row) ->
        Album(
            id = albumId,
            name = row[DownloadedAlbums.name],
            artists = artistIdsByAlbum[albumId].orEmpty().mapNotNull { artists[it] },
            songCount = row[DownloadedAlbums.songCount],
            releaseDate = row[DownloadedAlbums.releaseDate]?.toPlatformLocalDateISO(),
            totalDuration = row[DownloadedAlbums.totalDuration],
            totalSize = row[DownloadedAlbums.totalSize],
            coverId = row[DownloadedAlbums.cover]?.value,
            originalId = row[DownloadedAlbums.originalId],
            musicbrainzId = row[DownloadedAlbums.musicBrainzId],
            genres = genres[albumId].orEmpty()
        )
    }
}

private fun mapSongs(rows: List<ResultRow>): List<UserSong> {
    if (rows.isEmpty()) return emptyList()

    val songIds = rows.map { it[DownloadedSongs.id].value }

    val artistIdsBySong = HashMap<UUID, MutableList<UUID>>()
    songIds.distinct().chunked(SQL_CHUNK_SIZE).forEach { chunk ->
        DownloadedSongArtists
            .select(DownloadedSongArtists.songId, DownloadedSongArtists.artistId)
            .where { DownloadedSongArtists.songId inList chunk }
            .forEach { row ->
                artistIdsBySong.getOrPut(row[DownloadedSongArtists.songId].value) { mutableListOf() }
                    .add(row[DownloadedSongArtists.artistId].value)
            }
    }

    val albums = loadAlbums(rows.mapNotNull { it[DownloadedSongs.albumId]?.value })
    val artists = loadArtists(artistIdsBySong.values.flatten())
    val genres = loadGenres(DownloadedSongGenres, DownloadedSongGenres.songId, songIds)

    return rows.map { row ->
        val songId = row[DownloadedSongs.id].value
        UserSong(
            id = songId,
            title = row[DownloadedSongs.title],
            tags = decodeTitleTags(row[DownloadedSongs.tags]),
            artists = artistIdsBySong[songId].orEmpty().mapNotNull { artists[it] },
            album = row[DownloadedSongs.albumId]?.let { albums[it.value] },
            duration = row[DownloadedSongs.duration],
            explicit = row[DownloadedSongs.explicit],
            releaseDate = row[DownloadedSongs.releaseDate]?.toPlatformLocalDateISO(),
            lyrics = row[DownloadedSongs.lyrics],
            path = row[DownloadedSongs.filePath],
            originalUrl = row[DownloadedSongs.originalUrl],
            trackNumber = row[DownloadedSongs.trackNumber],
            discNumber = row[DownloadedSongs.discNumber],
            copyright = row[DownloadedSongs.copyright],
            audio = AudioInfo(
                codec = row[DownloadedSongs.codec],
                sampleRate = row[DownloadedSongs.sampleRate],
                bitsPerSample = row[DownloadedSongs.bitsPerSample],
                bitRate = row[DownloadedSongs.bitRate],
                fileSize = row[DownloadedSongs.fileSize],
                channels = row[DownloadedSongs.channels],
            ),
            audioStartMs = row[DownloadedSongs.audioStartMs],
            coverId = row[DownloadedSongs.cover]?.value,
            musicBrainzId = row[DownloadedSongs.musicBrainzId],
            genres = genres[songId].orEmpty(),
            isFavourite = row[DownloadedSongs.isFavourite],
            userSongCreatedAt = row[DownloadedSongs.createdAt]?.let { platformDateFromEpochMilliseconds(it) },
            userSongUpdatedAt = row[DownloadedSongs.updatedAt]?.let { platformDateFromEpochMilliseconds(it) }
        )
    }
}

internal fun getSongsInternal(ids: Collection<UUID>): List<UserSong> =
    ids.chunked(SQL_CHUNK_SIZE).flatMap { chunk ->
        mapSongs(DownloadedSongs.selectAll().where { DownloadedSongs.id inList chunk }.toList())
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
        it[tags] = encodeTitleTags(song.tags)
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
        val songAudio = song.effectiveAudio
        it[codec] = songAudio?.codec ?: ""
        it[sampleRate] = songAudio?.sampleRate ?: 0
        it[bitsPerSample] = songAudio?.bitsPerSample ?: 0
        it[bitRate] = songAudio?.bitRate ?: 0L
        it[fileSize] = songAudio?.fileSize ?: 0L
        it[channels] = songAudio?.channels ?: 0
        it[audioStartMs] = song.audioStartMs
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

class ExposedSongGuessRepository(private val json: Json) : SongGuessRepository {
    override suspend fun insert(userId: PlatformUUID, entry: LeaderboardEntry): LeaderboardEntry {
        val id = dbQuery {
            SongGuessGames.insertAndGetId {
                it[SongGuessGames.userId] = userId
                it[playedAt] = entry.playedAt
                it[score] = entry.score
                it[maxScore] = entry.maxScore
                it[rounds] = entry.config.rounds
                it[config] = json.encodeToString(entry.config)
                it[roundResults] = json.encodeToString(entry.roundResults)
            }
        }
        return entry.copy(id = id.value.toLong())
    }

    override suspend fun getAll(userId: PlatformUUID): List<LeaderboardEntry> = dbQuery {
        SongGuessGames.selectAll()
            .where { SongGuessGames.userId eq userId }
            .orderBy(SongGuessGames.score to SortOrder.DESC, SongGuessGames.playedAt to SortOrder.DESC)
            .map {
                LeaderboardEntry(
                    id = it[SongGuessGames.id].value.toLong(),
                    playedAt = it[SongGuessGames.playedAt],
                    score = it[SongGuessGames.score],
                    maxScore = it[SongGuessGames.maxScore],
                    config = json.decodeFromString(it[SongGuessGames.config]),
                    roundResults = json.decodeFromString(it[SongGuessGames.roundResults])
                )
            }
    }

    override suspend fun clear(userId: PlatformUUID) {
        dbQuery { SongGuessGames.deleteWhere { SongGuessGames.userId eq userId } }
    }

    override suspend fun saveGame(userId: PlatformUUID, game: SavedGame) {
        dbQuery {
            SongGuessSavedGames.upsert(SongGuessSavedGames.userId) {
                it[SongGuessSavedGames.userId] = userId
                it[updatedAt] = currentTimeMillis()
                it[state] = json.encodeToString(game)
            }
        }
    }

    override suspend fun loadGame(userId: PlatformUUID): SavedGame? = dbQuery {
        SongGuessSavedGames.selectAll()
            .where { SongGuessSavedGames.userId eq userId }
            .firstOrNull()
            ?.let { json.decodeFromString<SavedGame>(it[SongGuessSavedGames.state]) }
    }

    override suspend fun clearGame(userId: PlatformUUID) {
        dbQuery { SongGuessSavedGames.deleteWhere { SongGuessSavedGames.userId eq userId } }
    }
}

class ExposedSettingsSyncRepository : SettingsSyncRepository {
    override suspend fun getAll(): Map<String, SettingsSyncKnown> = dbQuery {
        SettingsSyncKnownEntries.selectAll().associate {
            it[SettingsSyncKnownEntries.key] to SettingsSyncKnown(
                value = it[SettingsSyncKnownEntries.value],
                version = it[SettingsSyncKnownEntries.version]
            )
        }
    }

    override suspend fun putAll(entries: Map<String, SettingsSyncKnown>) {
        if (entries.isEmpty()) return
        dbQuery {
            for ((key, known) in entries) {
                SettingsSyncKnownEntries.upsert(SettingsSyncKnownEntries.key) {
                    it[SettingsSyncKnownEntries.key] = key
                    it[SettingsSyncKnownEntries.value] = known.value
                    it[SettingsSyncKnownEntries.version] = known.version
                }
            }
        }
    }

    override suspend fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        dbQuery {
            keys.chunked(SQL_CHUNK_SIZE).forEach { chunk ->
                SettingsSyncKnownEntries.deleteWhere { SettingsSyncKnownEntries.key inList chunk }
            }
        }
    }

    override suspend fun clear() {
        dbQuery { SettingsSyncKnownEntries.deleteAll() }
    }
}
