package dev.dertyp.synara.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.synara.utils.AppDispatchers
import dev.dertyp.toPlatformUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class SqlDelightRecentlyPlayedRepository(
    private val database: SynaraDatabase,
    private val json: Json
) : RecentlyPlayedRepository {
    private val queries = database.recentlyPlayedQueries

    override suspend fun insertSong(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        queries.insertSong(userId.toString(), song.id.toString(), timestamp, json.encodeToString(song))
    }

    override suspend fun insertAlbum(userId: PlatformUUID, album: Album, timestamp: Long) {
        queries.insertAlbum(userId.toString(), album.id.toString(), timestamp, json.encodeToString(album))
    }

    override suspend fun insertArtist(userId: PlatformUUID, artist: Artist, timestamp: Long) {
        queries.insertArtist(userId.toString(), artist.id.toString(), timestamp, json.encodeToString(artist))
    }

    override suspend fun getSongs(userId: PlatformUUID, limit: Long): List<UserSong> {
        return queries.getSongs(userId.toString(), limit).executeAsList().map { json.decodeFromString(it.payload) }
    }

    override suspend fun getAlbums(userId: PlatformUUID, limit: Long): List<Album> {
        return queries.getAlbums(userId.toString(), limit).executeAsList().map { json.decodeFromString(it.payload) }
    }

    override suspend fun getArtists(userId: PlatformUUID, limit: Long): List<Artist> {
        return queries.getArtists(userId.toString(), limit).executeAsList().map { json.decodeFromString(it.payload) }
    }

    override fun getSongsFlow(userId: PlatformUUID, limit: Long): Flow<List<UserSong>> {
        return queries.getSongs(userId.toString(), limit).asFlow().mapToList(AppDispatchers.io).map { list ->
            list.map { json.decodeFromString(it.payload) }
        }
    }

    override fun getAlbumsFlow(userId: PlatformUUID, limit: Long): Flow<List<Album>> {
        return queries.getAlbums(userId.toString(), limit).asFlow().mapToList(AppDispatchers.io).map { list ->
            list.map { json.decodeFromString(it.payload) }
        }
    }

    override fun getArtistsFlow(userId: PlatformUUID, limit: Long): Flow<List<Artist>> {
        return queries.getArtists(userId.toString(), limit).asFlow().mapToList(AppDispatchers.io).map { list ->
            list.map { json.decodeFromString(it.payload) }
        }
    }

    override suspend fun deleteAllSongs(userId: PlatformUUID) {
        queries.deleteAllSongs(userId.toString())
    }

    override suspend fun deleteAllAlbums(userId: PlatformUUID) {
        queries.deleteAllAlbums(userId.toString())
    }

    override suspend fun deleteAllArtists(userId: PlatformUUID) {
        queries.deleteAllArtists(userId.toString())
    }
}

class SqlDelightScrobbleQueueRepository(
    private val database: SynaraDatabase,
    private val json: Json
) : ScrobbleQueueRepository {
    private val queries = database.scrobbleQueueQueries

    override suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long, target: String) {
        queries.insert(userId.toString(), json.encodeToString(song), timestamp, target)
    }

    override suspend fun getAll(userId: PlatformUUID, target: String): List<ScrobbleQueueEntry> {
        return queries.getAll(userId.toString(), target).executeAsList().map {
            ScrobbleQueueEntry(
                it.id,
                it.userId.toPlatformUUID(),
                json.decodeFromString(it.payload),
                it.timestamp,
                it.target
            )
        }
    }

    override suspend fun peek(userId: PlatformUUID, target: String): ScrobbleQueueEntry? {
        return queries.peek(userId.toString(), target).executeAsOneOrNull()?.let {
            ScrobbleQueueEntry(
                it.id,
                it.userId.toPlatformUUID(),
                json.decodeFromString(it.payload),
                it.timestamp,
                it.target
            )
        }
    }

    override suspend fun delete(id: Long) {
        queries.delete(id)
    }

    override suspend fun getCount(userId: PlatformUUID, target: String): Long {
        return queries.getCount(userId.toString(), target).executeAsOne()
    }
}

class SqlDelightLocalHistoryRepository(
    private val database: SynaraDatabase,
    private val json: Json
) : LocalHistoryRepository {
    private val queries = database.scrobbleQueueQueries

    override suspend fun insert(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        queries.insertHistory(userId.toString(), song.id.toString(), timestamp, json.encodeToString(song))
    }

    override suspend fun get(userId: PlatformUUID, limit: Long): List<LocalHistoryEntry> {
        return queries.getHistory(userId.toString(), limit).executeAsList().map {
            LocalHistoryEntry(
                it.id,
                it.userId.toPlatformUUID(),
                it.song_id.toPlatformUUID(),
                it.timestamp,
                json.decodeFromString(it.payload)
            )
        }
    }
}

class SqlDelightDatabaseMigrationRepository(
    private val database: SynaraDatabase
) : DatabaseMigrationRepository {
    override suspend fun migrateUserIds(newUserId: PlatformUUID) {
        database.recentlyPlayedQueries.migrateUserId(newUserId.toString())
        database.recentlyPlayedQueries.migrateAlbumUserId(newUserId.toString())
        database.recentlyPlayedQueries.migrateArtistUserId(newUserId.toString())
        database.scrobbleQueueQueries.migrateQueueUserId(newUserId.toString())
        database.scrobbleQueueQueries.migrateHistoryUserId(newUserId.toString())
    }
}
