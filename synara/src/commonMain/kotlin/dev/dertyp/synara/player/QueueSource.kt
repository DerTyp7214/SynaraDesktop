package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.BaseSong
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.SongTag
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface QueueSource {
    val id: String
    suspend fun getSize(): Int
    suspend fun getSongAt(index: Int): BaseSong?
    fun getIdFlow(): Flow<PlatformUUID>
}

class ListQueueSource(
    override val id: String,
    private val songs: List<BaseSong>
) : QueueSource {
    override suspend fun getSize(): Int = songs.size
    override suspend fun getSongAt(index: Int): BaseSong? = songs.getOrNull(index)
    override fun getIdFlow(): Flow<PlatformUUID> = songs.map { it.id }.asFlow()
}

abstract class BasePagedQueueSource(override val id: String) : QueueSource {
    protected var totalCount: Int = -1
    protected val totalCountMutex = Mutex()
    protected val cache = mutableMapOf<Int, BaseSong>()
    protected val cacheMutex = Mutex()
    private val inFlightPages = mutableMapOf<Int, Deferred<PaginatedResponse<UserSong>>>()
    private val inFlightMutex = Mutex()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    protected val pageSize = 50

    protected abstract suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong>
    protected abstract fun fetchIdFlow(): Flow<PlatformUUID>

    override suspend fun getSize(): Int {
        if (totalCount == -1) {
            totalCountMutex.withLock {
                if (totalCount == -1) {
                    val resp = fetchPage(0, 1)
                    totalCount = resp.total
                }
            }
        }
        return totalCount
    }

    override suspend fun getSongAt(index: Int): BaseSong? {
        cacheMutex.withLock {
            cache[index]?.let { return it }
        }
        
        val page = index / pageSize

        val deferred = inFlightMutex.withLock {
            inFlightPages.getOrPut(page) {
                fetchScope.async {
                    try {
                        val resp = fetchPage(page, pageSize)

                        cacheMutex.withLock {
                            resp.data.forEachIndexed { i, song ->
                                cache[page * pageSize + i] = song
                            }
                        }

                        if (totalCount == -1) {
                            totalCountMutex.withLock {
                                if (totalCount == -1) totalCount = resp.total
                            }
                        }
                        resp
                    } finally {
                        inFlightMutex.withLock {
                            inFlightPages.remove(page)
                        }
                    }
                }
            }
        }

        return try {
            deferred.await()
            cacheMutex.withLock { cache[index] }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    override fun getIdFlow(): Flow<PlatformUUID> = fetchIdFlow()
}

class AllSongsQueueSource(
    private val songService: ISongService,
    private val explicit: Boolean = true,
    private val tags: List<SongTag> = emptyList(),
    private val invertTags: Boolean = false,
    id: String = "all_songs"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return songService.allSongs(page, pageSize, explicit, tags, invertTags)
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> {
        return songService.allSongIds(explicit, tags, invertTags)
    }
}

class LikedSongsQueueSource(
    private val songService: ISongService,
    private val explicit: Boolean = true,
    id: String = "liked_songs"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return songService.likedSongs(page, pageSize, explicit)
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> {
        return songService.likedSongIds(explicit)
    }
}

class SearchQueueSource(
    private val songService: ISongService,
    private val query: String,
    private val explicit: Boolean = true,
    id: String = "search_$query"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return songService.rankedSearch(page, pageSize, query, explicit)
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> = flow {
        val firstPage = fetchPage(0, pageSize)
        firstPage.data.forEach { emit(it.id) }
        
        var currentPage = 1
        var fetched = firstPage.data.size
        while (fetched < firstPage.total) {
            val resp = fetchPage(currentPage++, pageSize)
            resp.data.forEach { emit(it.id) }
            fetched += resp.data.size
            if (resp.data.isEmpty()) break
        }
    }
}

class AlbumQueueSource(
    private val songService: ISongService,
    private val albumId: PlatformUUID,
    id: String = "album_$albumId"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return songService.byAlbum(page, pageSize, albumId)
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> {
        return songService.songIdsByAlbum(albumId)
    }
}

class ArtistQueueSource(
    private val songService: ISongService,
    private val artistId: PlatformUUID,
    id: String = "artist_$artistId"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return songService.byArtist(page, pageSize, artistId)
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> {
        return songService.songIdsByArtist(artistId)
    }
}

class PlaylistQueueSource(
    private val songService: ISongService,
    private val playlistId: PlatformUUID,
    private val isUserPlaylist: Boolean,
    id: String = "playlist_$playlistId"
) : BasePagedQueueSource(id) {
    override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
        return if (isUserPlaylist) {
            songService.byUserPlaylist(page, pageSize, playlistId)
        } else {
            songService.byPlaylist(page, pageSize, playlistId)
        }
    }

    override fun fetchIdFlow(): Flow<PlatformUUID> {
        return if (isUserPlaylist) {
            songService.songIdsByUserPlaylist(playlistId)
        } else {
            songService.songIdsByPlaylist(playlistId)
        }
    }
}
