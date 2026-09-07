package dev.dertyp.synara.services

import com.russhwolf.settings.Settings
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.data.effectiveAudio
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.db.*
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.get
import dev.dertyp.synara.settings.put
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class DownloadManager(
    private val songService: ISongService,
    private val albumService: IAlbumService,
    private val artistService: IArtistService,
    private val playlistService: IUserPlaylistService,
    private val storageService: LocalStorageService,
    private val libraryRepository: LibraryRepository,
    private val settings: Settings
) : IDownloadManager {
    companion object {
        private const val MAX_SPEED_BYTES_PER_SECOND = 10L * 1024 * 1024
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _queue = MutableStateFlow<List<UserSong>>(emptyList())
    override val queue: StateFlow<List<UserSong>> = _queue.asStateFlow()

    private val _currentDownload = MutableStateFlow<DownloadProgress?>(null)
    override val currentDownload: StateFlow<DownloadProgress?> = _currentDownload.asStateFlow()

    private val queueUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val cleanupMutex = Mutex()

    private val libraryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val savedIds: StateFlow<SavedLibraryIds?> =
        merge(libraryRepository.observeChanges(), libraryChanged)
            .onStart { emit(Unit) }
            .conflate()
            .mapNotNull {
                try {
                    libraryRepository.getExplicitlySavedIds()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            .stateIn(scope, SharingStarted.Lazily, null)

    private val queuedIds: StateFlow<Set<PlatformUUID>> =
        _queue.map { q -> q.mapTo(HashSet()) { it.id } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val currentDownloadId: StateFlow<PlatformUUID?> =
        _currentDownload.map { it?.song?.id }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    override fun getDownloadStatus(songId: PlatformUUID): Flow<DownloadStatus> =
        combine(queuedIds, currentDownloadId, savedIds) { q, current, saved ->
            when {
                current == songId -> DownloadStatus.Downloading
                songId in q -> DownloadStatus.Queued
                saved != null && songId in saved.songs -> DownloadStatus.Downloaded
                else -> DownloadStatus.NotDownloaded
            }
        }.distinctUntilChanged()

    override fun isAlbumDownloaded(albumId: PlatformUUID): Flow<Boolean> =
        savedIds.map { it != null && albumId in it.albums }.distinctUntilChanged()

    override fun isArtistDownloaded(artistId: PlatformUUID): Flow<Boolean> =
        savedIds.map { it != null && artistId in it.artists }.distinctUntilChanged()

    override fun isPlaylistDownloaded(playlistId: PlatformUUID): Flow<Boolean> =
        savedIds.map { it != null && playlistId in it.playlists }.distinctUntilChanged()

    private suspend fun awaitSavedIds(): SavedLibraryIds = savedIds.filterNotNull().first()

    init {
        scope.launch {
            queueUpdateFlow
                .onStart { emit(Unit) }
                .debounce(100.milliseconds)
                .takeWhile { isActive }
                .collect {
                    while (_queue.value.isNotEmpty()) {
                        val nextSong = _queue.value.first()
                        try {
                            performDownload(nextSong)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            _queue.update { q -> q.filter { it.id != nextSong.id } }
                        }
                    }
                }
        }
    }

    override fun downloadSong(songId: PlatformUUID, explicitlySaved: Boolean) {
        scope.launch {
            val song = songService.byId(songId) ?: return@launch
            if (explicitlySaved) {
                libraryRepository.saveSongMetadata(song, true)
            }
            addToQueue(song)
        }
    }

    override fun downloadAlbum(albumId: PlatformUUID, explicitlySaved: Boolean) {
        scope.launch {
            val album = albumService.byId(albumId) ?: return@launch
            libraryRepository.saveAlbumMetadata(album, explicitlySaved)
            
            var page = 0
            while (true) {
                val response = songService.byAlbum(page, 150, album.id)
                if (response.data.isEmpty()) break
                response.data.forEach { addToQueue(it) }
                if (!response.hasNextPage) break
                page++
            }
        }
    }

    override fun downloadArtist(artistId: PlatformUUID, explicitlySaved: Boolean) {
        scope.launch {
            val artist = artistService.byId(artistId) ?: return@launch
            libraryRepository.saveArtistMetadata(artist, explicitlySaved)
            
            var page = 0
            while (true) {
                val response = albumService.byArtist(page, 150, artist.id)
                if (response.data.isEmpty()) break
                response.data.forEach { downloadAlbum(it.id, false) }
                if (!response.hasNextPage) break
                page++
            }
        }
    }

    override fun downloadPlaylist(playlistId: PlatformUUID, explicitlySaved: Boolean) {
        scope.launch {
            val playlist = playlistService.byId(playlistId) ?: return@launch
            libraryRepository.savePlaylistMetadata(playlist, explicitlySaved)

            var page = 0
            while (true) {
                val response = songService.byUserPlaylist(page, 150, playlist.id)
                if (response.data.isEmpty()) break
                libraryRepository.addSongsToPlaylist(playlist.id, response.data.map { it.id })
                response.data.forEach { addToQueue(it) }
                if (!response.hasNextPage) break
                page++
            }
        }
    }

    override fun downloadFavorites() {
        settings.put(SettingKey.DownloadFavorites, true)
        scope.launch {
            var page = 0
            while (true) {
                val response = songService.likedSongs(page, 150, false)
                if (response.data.isEmpty()) break
                response.data.forEach { addToQueue(it) }
                if (!response.hasNextPage) break
                page++
            }
        }
    }

    private suspend fun addToQueue(song: UserSong) {
        if (_queue.value.any { it.id == song.id } || _currentDownload.value?.song?.id == song.id) return
        if (song.id in awaitSavedIds().songs && File(storageService.getInternalSongPath(song)).exists()) return
        _queue.update { it + song }
        queueUpdateFlow.tryEmit(Unit)
    }

    private suspend fun performDownload(song: UserSong) {
        val internalPath = storageService.getInternalSongPath(song)
        val file = File(internalPath)
        
        if (!file.exists()) {
            val flow = songService.streamSong(song.id, chunkSize = 1024 * 64)
            if (flow != null) {
                file.parentFile?.mkdirs()
                withContext(Dispatchers.IO) {
                    var downloaded = 0L
                    val total = song.effectiveAudio?.fileSize ?: 0L
                    _currentDownload.value = DownloadProgress(song, 0, total)
                    
                    val startTime = System.currentTimeMillis()

                    FileOutputStream(file).use { out ->
                        flow.collect { bytes ->
                            runInterruptible {
                                out.write(bytes)
                            }
                            downloaded += bytes.size
                            _currentDownload.value = DownloadProgress(song, downloaded, total)

                            val expectedTime = (downloaded.toDouble() / MAX_SPEED_BYTES_PER_SECOND) * 1000
                            val actualTime = System.currentTimeMillis() - startTime
                            if (actualTime < expectedTime) {
                                delay((expectedTime - actualTime).toLong().coerceAtMost(250).milliseconds)
                            }

                            yield()
                        }
                    }
                }
                storageService.linkSongToSystemMusicDir(song)
                delay(10.milliseconds)
            }
        }
        
        libraryRepository.saveSongMetadata(song, false)
        _currentDownload.value = null
    }

    override fun removeSong(id: PlatformUUID) {
        scope.launch {
            _queue.update { q -> q.filter { it.id != id } }
            dbQuery {
                DownloadedSongs.update({ DownloadedSongs.id eq id }) {
                    it[explicitlySaved] = false
                }
            }
            cleanup()
        }
    }

    override fun removeAlbum(id: PlatformUUID) {
        scope.launch {
            dbQuery {
                DownloadedAlbums.update({ DownloadedAlbums.id eq id }) {
                    it[explicitlySaved] = false
                }
            }
            cleanup()
        }
    }

    override fun removeArtist(id: PlatformUUID) {
        scope.launch {
            dbQuery {
                DownloadedArtists.update({ DownloadedArtists.id eq id }) {
                    it[explicitlySaved] = false
                }
            }
            cleanup()
        }
    }

    override fun removePlaylist(id: PlatformUUID) {
        scope.launch {
            dbQuery {
                DownloadedUserPlaylists.update({ DownloadedUserPlaylists.id eq id }) {
                    it[explicitlySaved] = false
                }
            }
            cleanup()
        }
    }

    suspend fun cleanup(): Unit = cleanupMutex.withLock {
        val downloadFavoritesEnabled = settings.get(SettingKey.DownloadFavorites, false)

        val toDelete: List<Pair<PlatformUUID, UserSong?>> = dbQuery {
            // Find entities to keep
            val explicitlySavedSongs = DownloadedSongs.select(DownloadedSongs.id)
                .where {
                    if (downloadFavoritesEnabled) {
                        (DownloadedSongs.explicitlySaved eq true) or (DownloadedSongs.isFavourite eq true)
                    } else {
                        DownloadedSongs.explicitlySaved eq true
                    }
                }
                .map { it[DownloadedSongs.id].value }
                .toSet()

            val explicitlySavedAlbums = DownloadedAlbums.select(DownloadedAlbums.id)
                .where { DownloadedAlbums.explicitlySaved eq true }
                .map { it[DownloadedAlbums.id].value }
                .toSet()

            val explicitlySavedArtists = DownloadedArtists.select(DownloadedArtists.id)
                .where { DownloadedArtists.explicitlySaved eq true }
                .map { it[DownloadedArtists.id].value }
                .toSet()

            val explicitlySavedPlaylists = DownloadedUserPlaylists.select(DownloadedUserPlaylists.id)
                .where { DownloadedUserPlaylists.explicitlySaved eq true }
                .map { it[DownloadedUserPlaylists.id].value }
                .toSet()

            val playlistSongs = DownloadedUserPlaylistSongs.select(DownloadedUserPlaylistSongs.songId)
                .where { DownloadedUserPlaylistSongs.playlistId inList explicitlySavedPlaylists }
                .map { it[DownloadedUserPlaylistSongs.songId].value }
                .toSet()

            val songsToKeep = mutableSetOf<UUID>()
            songsToKeep.addAll(explicitlySavedSongs)
            songsToKeep.addAll(playlistSongs)

            if (explicitlySavedAlbums.isNotEmpty()) {
                DownloadedSongs.select(DownloadedSongs.id)
                    .where { DownloadedSongs.albumId inList explicitlySavedAlbums }
                    .forEach { songsToKeep.add(it[DownloadedSongs.id].value) }
            }

            if (explicitlySavedArtists.isNotEmpty()) {
                DownloadedSongArtists.select(DownloadedSongArtists.songId)
                    .where { DownloadedSongArtists.artistId inList explicitlySavedArtists }
                    .forEach { songsToKeep.add(it[DownloadedSongArtists.songId].value) }
                
                val artistAlbums = DownloadedAlbumArtists.select(DownloadedAlbumArtists.albumId)
                    .where { DownloadedAlbumArtists.artistId inList explicitlySavedArtists }
                    .map { it[DownloadedAlbumArtists.albumId].value }
                
                if (artistAlbums.isNotEmpty()) {
                    DownloadedSongs.select(DownloadedSongs.id)
                        .where { DownloadedSongs.albumId inList artistAlbums }
                        .forEach { songsToKeep.add(it[DownloadedSongs.id].value) }
                }
            }

            val allSongs = DownloadedSongs.selectAll().map { it[DownloadedSongs.id].value }
            val songsToDelete = allSongs.filter { it !in songsToKeep }

            val songs = getSongsInternal(songsToDelete).associateBy { it.id }
            songsToDelete.map { id -> id to songs[id] }
        }

        withContext(Dispatchers.IO) {
            toDelete.forEach { (_, song) ->
                song?.let {
                    File(storageService.getInternalSongPath(it)).delete()
                    storageService.unlinkSongFromSystemMusicDir(it)
                }
            }
        }

        dbQuery {
            val ids = toDelete.map { it.first }

            ids.chunked(500).forEach { chunk ->
                DownloadedSongs.deleteWhere { DownloadedSongs.id inList chunk }
                DownloadedUserPlaylistSongs.deleteWhere { DownloadedUserPlaylistSongs.songId inList chunk }
            }

            val liveSongs = DownloadedSongs.select(DownloadedSongs.id)
            DownloadedSongArtists.deleteWhere { DownloadedSongArtists.songId notInSubQuery liveSongs }
            DownloadedSongGenres.deleteWhere { DownloadedSongGenres.songId notInSubQuery liveSongs }
            DownloadedPlaylistSongs.deleteWhere { DownloadedPlaylistSongs.songId notInSubQuery liveSongs }

            // Cleanup Albums
            val albumsWithSongs = DownloadedSongs.select(DownloadedSongs.albumId)
                .mapNotNull { it[DownloadedSongs.albumId]?.value }
                .toSet()

            DownloadedAlbums.deleteWhere {
                (DownloadedAlbums.id notInList albumsWithSongs) and (DownloadedAlbums.explicitlySaved eq false)
            }

            val liveAlbums = DownloadedAlbums.select(DownloadedAlbums.id)
            DownloadedAlbumArtists.deleteWhere { DownloadedAlbumArtists.albumId notInSubQuery liveAlbums }
            DownloadedAlbumGenres.deleteWhere { DownloadedAlbumGenres.albumId notInSubQuery liveAlbums }

            // Cleanup Artists
            val artistsWithSongs = DownloadedSongArtists.select(DownloadedSongArtists.artistId)
                .map { it[DownloadedSongArtists.artistId].value }
                .toSet()

            val artistsWithAlbums = DownloadedAlbumArtists.select(DownloadedAlbumArtists.artistId)
                .map { it[DownloadedAlbumArtists.artistId].value }
                .toSet()

            val activeArtists = artistsWithSongs + artistsWithAlbums

            DownloadedArtists.deleteWhere {
                (DownloadedArtists.id notInList activeArtists) and (DownloadedArtists.explicitlySaved eq false)
            }

            val liveArtists = DownloadedArtists.select(DownloadedArtists.id)
            DownloadedArtistMembers.deleteWhere {
                (DownloadedArtistMembers.groupId notInSubQuery liveArtists) or
                    (DownloadedArtistMembers.memberId notInSubQuery liveArtists)
            }
            DownloadedArtistGenres.deleteWhere { DownloadedArtistGenres.artistId notInSubQuery liveArtists }
        }

        libraryChanged.tryEmit(Unit)
    }
}
