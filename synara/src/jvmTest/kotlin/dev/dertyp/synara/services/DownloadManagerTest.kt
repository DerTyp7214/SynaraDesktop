package dev.dertyp.synara.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.BaseSong
import dev.dertyp.data.EntityChange
import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityChangeWindow
import dev.dertyp.data.EntityType
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserProfile
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IEntityChangeService
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.services.models.LyricsCandidate
import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsProviderInfo
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.synara.Config
import dev.dertyp.synara.db.*
import dev.dertyp.synara.lyrics.LyricsRepository
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.settings.getOrNull
import dev.dertyp.synara.settings.put
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.compose.resources.getString
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.download_failed
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    private class TempStorage(private val root: File) : LocalStorageService {
        override fun getConfigDir(): String = root.path
        override fun getCacheDir(): String = root.path
        override fun getDataDir(): String = root.path
        override fun getSystemMusicDir(): String = root.path
        override fun getInternalSongDir(): String = File(root, "songs").apply { mkdirs() }.path
        override fun getInternalSongPath(song: BaseSong): String = File(getInternalSongDir(), song.path.trimStart('/')).path
        override fun linkSongToSystemMusicDir(song: BaseSong) {}
        override fun unlinkSongFromSystemMusicDir(song: BaseSong) {}
    }

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)
    private val dispatchers = object : SynaraDispatchers {
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
    }

    private val root: File = Files.createTempDirectory("download-manager-test").toFile()
    private val storage = TempStorage(root)

    private val songService = mockk<ISongService>()
    private val downloadService = mockk<ISongService>()
    private val serverReachable = MutableStateFlow(false)
    private val connectionState = MutableStateFlow(RpcServiceManager.ConnectionState.Loading)
    private val rpcManager = mockk<RpcServiceManager>(relaxed = true) {
        every { getService(ISongService::class, any()) } returns downloadService
        every { isServerReachable } returns serverReachable
        every { connectionState } returns this@DownloadManagerTest.connectionState
    }
    private val lyricsService = mockk<ILyricsCandidateService> {
        coEvery { getLyrics(any(), any()) } returns null
    }
    private val entityChangeService = mockk<IEntityChangeService>()
    private val offlineLyricsStore = mockk<OfflineLyricsStore>(relaxed = true)
    private val lyricsRepository = mockk<LyricsRepository>(relaxed = true)
    private val settings = InMemorySettings()
    private val libraryRepository = mockk<LibraryRepository>(relaxed = true) {
        every { observeChanges() } returns emptyFlow()
        coEvery { getExplicitlySavedIds() } returns SavedLibraryIds(emptySet(), emptySet(), emptySet(), emptySet())
    }
    private val playlistService = mockk<IUserPlaylistService>()
    private val userService = mockk<IUserService>()
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val snackbarManager = mockk<SnackbarManager>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)

    private var manager: DownloadManager? = null
    private var database: Database? = null

    @AfterTest
    fun tearDown() {
        manager?.stop()
        scheduler.advanceUntilIdle()
        database?.let {
            TransactionManager.closeAndUnregister(it)
            ConfigFixture.resetToDefaults()
            stopTestKoin()
        }
        root.deleteRecursively()
    }

    private fun connectLibrary(): Database {
        startTestKoin()
        ConfigFixture.resetToDefaults()
        val connected = Database.connect("jdbc:sqlite:${File(root, "library.db").path}", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = connected
        transaction(connected) {
            SchemaUtils.create(
                DownloadedImages,
                DownloadedUsers,
                DownloadedSongs,
                DownloadedAlbums,
                DownloadedArtists,
                DownloadedGenres,
                DownloadedPlaylists,
                DownloadedUserPlaylists,
                DownloadedSongArtists,
                DownloadedAlbumArtists,
                DownloadedArtistMembers,
                DownloadedPlaylistSongs,
                DownloadedUserPlaylistSongs,
                DownloadedSongGenres,
                DownloadedAlbumGenres,
                DownloadedArtistGenres,
                DownloadedSongLyrics
            )
        }
        database = connected
        return connected
    }

    private fun storedSong(index: Int): UserSong {
        val song = testSong(index)
        runBlocking { ExposedLibraryRepository().saveSongMetadata(song, false) }
        File(storage.getInternalSongPath(song)).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(4))
        return song
    }

    private fun storedSongIds(): Set<PlatformUUID> = transaction(database!!) {
        DownloadedSongs.selectAll().mapTo(HashSet()) { it[DownloadedSongs.id].value }
    }

    private fun isKept(song: UserSong): Boolean =
        song.id in storedSongIds() && File(storage.getInternalSongPath(song)).exists()

    private fun isDeleted(song: UserSong): Boolean =
        song.id !in storedSongIds() && !File(storage.getInternalSongPath(song)).exists()

    private fun song(fileSize: Long) = UserSong(
        id = randomPlatformUUID(),
        title = "Song",
        artists = emptyList(),
        album = null,
        duration = 240_000,
        explicit = false,
        path = "/Artist/Album/song.flac",
        audio = AudioInfo("flac", 44100, 16, 900, fileSize, 2)
    )

    private fun createManager(dispatchers: SynaraDispatchers = this.dispatchers): DownloadManager = DownloadManager(
        songService = songService,
        albumService = mockk(),
        artistService = mockk(),
        playlistService = playlistService,
        userService = userService,
        lyricsService = lyricsService,
        entityChangeService = entityChangeService,
        offlineLyricsStore = offlineLyricsStore,
        lyricsRepository = lyricsRepository,
        userRepository = userRepository,
        storageService = storage,
        libraryRepository = libraryRepository,
        settings = settings,
        rpcManager = rpcManager,
        logger = logger,
        snackbarManager = snackbarManager,
        dispatchers = dispatchers
    ).also { manager = it }

    private fun download(song: UserSong, vararg streams: Flow<ByteArray>): DownloadManager {
        coEvery { songService.byId(song.id) } returns song
        every { downloadService.streamSong(song.id, any(), any()) } returnsMany streams.toList()
        val manager = createManager()
        manager.downloadSong(song.id, explicitlySaved = false)
        scheduler.advanceUntilIdle()
        return manager
    }

    private fun partFiles(): List<File> = root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    private fun failureMessage(song: UserSong): String = runBlocking { getString(Res.string.download_failed, song.title) }

    private fun playlistOf(creator: UserProfile) = UserPlaylist(
        id = randomPlatformUUID(),
        name = "Shared",
        songs = emptyList(),
        creator = creator.id,
        description = ""
    ).also {
        coEvery { playlistService.byId(it.id) } returns it
        coEvery { songService.byUserPlaylist(any(), any(), it.id) } returns PaginatedResponse(emptyList())
    }

    @Test
    fun downloadingAPlaylistOfAnotherUserStoresTheOwnerBeforeThePlaylist() {
        val owner = UserProfile(id = randomPlatformUUID(), username = "owner", displayName = "Owner", profileImageId = randomPlatformUUID())
        val playlist = playlistOf(owner)
        val storedOwner = User(id = owner.id, username = "owner", displayName = "Owner", profileImageId = owner.profileImageId)
        coEvery { userRepository.getUser(owner.id) } returns null
        coEvery { userService.allProfiles() } returns listOf(UserProfile(randomPlatformUUID(), "someone"), owner)

        createManager().downloadPlaylist(playlist.id, explicitlySaved = true)
        scheduler.advanceUntilIdle()

        coVerifyOrder {
            userRepository.saveUser(storedOwner)
            libraryRepository.savePlaylistMetadata(playlist, true)
        }
        coVerify(exactly = 1) { userRepository.saveUser(any()) }
    }

    @Test
    fun downloadingAPlaylistWhoseOwnerIsStoredRequestsNoProfiles() {
        val owner = UserProfile(id = randomPlatformUUID(), username = "me")
        val playlist = playlistOf(owner)
        coEvery { userRepository.getUser(owner.id) } returns User(id = owner.id, username = "me")

        createManager().downloadPlaylist(playlist.id, explicitlySaved = true)
        scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { libraryRepository.savePlaylistMetadata(playlist, true) }
        coVerify(exactly = 0) { userService.allProfiles() }
        coVerify(exactly = 0) { userRepository.saveUser(any()) }
    }

    @Test
    fun downloadingAPlaylistWhoseOwnerIsUnknownSavesNothing() {
        val owner = UserProfile(id = randomPlatformUUID(), username = "gone")
        val playlist = playlistOf(owner)
        coEvery { userRepository.getUser(owner.id) } returns null
        coEvery { userService.allProfiles() } returns emptyList()

        createManager().downloadPlaylist(playlist.id, explicitlySaved = true)
        scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { userRepository.saveUser(any()) }
        coVerify(exactly = 0) { libraryRepository.savePlaylistMetadata(any(), any()) }
    }

    @Test
    fun abortedStreamLeavesNoFileAndIsReportedAfterThreeAttempts() {
        val song = song(fileSize = 16)
        val message = failureMessage(song)

        val manager = download(
            song,
            flow {
                emit(ByteArray(4))
                emit(ByteArray(4))
                throw IOException("connection lost")
            }
        )

        assertFalse(File(storage.getInternalSongPath(song)).exists())
        assertEquals(emptyList(), partFiles())
        verify(exactly = 3) { downloadService.streamSong(song.id, any(), any()) }
        coVerify(exactly = 0) { libraryRepository.saveSongMetadata(any(), any()) }
        verify(exactly = 1) { snackbarManager.showSnackbar(message) }
        assertEquals(emptyList(), manager.queue.value)
        assertNull(manager.currentDownload.value)
    }

    @Test
    fun completeStreamIsMovedIntoPlaceAndSaved() {
        val song = song(fileSize = 8)

        val manager = download(
            song,
            flow {
                emit(byteArrayOf(1, 2, 3, 4))
                emit(byteArrayOf(5, 6, 7, 8))
            }
        )

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), File(storage.getInternalSongPath(song)).readBytes())
        assertEquals(emptyList(), partFiles())
        coVerify(exactly = 1) { libraryRepository.saveSongMetadata(song, false) }
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
        assertEquals(emptyList(), manager.queue.value)
        assertNull(manager.currentDownload.value)
    }

    @Test
    fun streamShorterThanTheExpectedSizeFails() {
        val song = song(fileSize = 16)
        val message = failureMessage(song)

        download(
            song,
            flow {
                emit(ByteArray(4))
                emit(ByteArray(4))
            }
        )

        assertFalse(File(storage.getInternalSongPath(song)).exists())
        assertEquals(emptyList(), partFiles())
        verify(exactly = 3) { downloadService.streamSong(song.id, any(), any()) }
        coVerify(exactly = 0) { libraryRepository.saveSongMetadata(any(), any()) }
        verify(exactly = 1) { snackbarManager.showSnackbar(message) }
    }

    @Test
    fun secondAttemptSucceedsWithoutAReport() {
        val song = song(fileSize = 8)

        download(
            song,
            flow {
                emit(ByteArray(4))
                throw IOException("connection lost")
            },
            flow {
                emit(ByteArray(4))
                emit(ByteArray(4))
            }
        )

        assertEquals(8L, File(storage.getInternalSongPath(song)).length())
        assertEquals(emptyList(), partFiles())
        verify(exactly = 2) { downloadService.streamSong(song.id, any(), any()) }
        coVerify(exactly = 1) { libraryRepository.saveSongMetadata(song, false) }
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun missingStreamFails() {
        val song = song(fileSize = 8)
        val message = failureMessage(song)
        coEvery { songService.byId(song.id) } returns song
        every { downloadService.streamSong(song.id, any(), any()) } returns null

        createManager().downloadSong(song.id, explicitlySaved = false)
        scheduler.advanceUntilIdle()

        assertFalse(File(storage.getInternalSongPath(song)).exists())
        coVerify(exactly = 0) { libraryRepository.saveSongMetadata(any(), any()) }
        verify(exactly = 1) { snackbarManager.showSnackbar(message) }
    }

    @Test
    fun stopCancelsTheRunningDownloadAndRemovesItsPartFile() {
        val song = song(fileSize = 16)

        val manager = download(
            song,
            flow {
                emit(ByteArray(4))
                awaitCancellation()
            }
        )
        assertEquals(1, partFiles().size)

        manager.stop()
        scheduler.advanceUntilIdle()

        assertFalse(File(storage.getInternalSongPath(song)).exists())
        assertEquals(emptyList(), partFiles())
        verify(exactly = 1) { downloadService.streamSong(song.id, any(), any()) }
        coVerify(exactly = 0) { libraryRepository.saveSongMetadata(any(), any()) }
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
    }

    @Test
    fun leftoverPartFilesAreRemovedAtStartup() {
        val album = File(storage.getInternalSongDir(), "Artist/Album").apply { mkdirs() }
        val leftover = File(album, "old.flac.part").apply { writeBytes(ByteArray(4)) }
        val finished = File(album, "finished.flac").apply { writeBytes(ByteArray(4)) }

        createManager()
        scheduler.advanceUntilIdle()

        assertFalse(leftover.exists())
        assertTrue(finished.exists())
    }

    @Test
    fun cleanupKeepsTheSongsOfDownloadedMixes() {
        connectLibrary()
        val inMix = storedSong(1)
        val loose = storedSong(2)
        Config.setDownloadedMixSongIds("mix:WEEK", listOf(inMix.id))

        val manager = createManager(UnconfinedDispatchers)
        runBlocking { manager.cleanup() }

        assertTrue(isKept(inMix))
        assertTrue(isDeleted(loose))
    }

    @Test
    fun cleanupDeletesTheStoredLyricsOfDeletedSongs() {
        connectLibrary()
        val inMix = storedSong(1)
        val loose = storedSong(2)
        Config.setDownloadedMixSongIds("mix:WEEK", listOf(inMix.id))
        val store = ExposedOfflineLyricsStore()
        runBlocking {
            store.saveLyrics(inMix.id, lyricsOf(inMix, "kept"))
            store.saveLyrics(loose.id, lyricsOf(loose, "dropped"))
        }

        val manager = createManager(UnconfinedDispatchers)
        runBlocking { manager.cleanup() }

        assertEquals("kept", runBlocking { store.getLyrics(inMix.id) }?.plain)
        assertNull(runBlocking { store.getLyrics(loose.id) })
    }

    @Test
    fun emptyMixResultKeepsTheStoredMembership() {
        connectLibrary()
        val inMix = storedSong(1)
        Config.setDownloadedMixSongIds("mix:WEEK", listOf(inMix.id))

        val manager = createManager(UnconfinedDispatchers)
        manager.downloadMix("mix:WEEK", emptyList())
        runBlocking { manager.cleanup() }

        assertEquals(mapOf("mix:WEEK" to listOf(inMix.id)), Config.downloadedMixSongIds.value)
        assertTrue(isKept(inMix))
    }

    @Test
    fun refreshingAMixReplacesItsMembershipAndDeletesDroppedSongs() {
        connectLibrary()
        val dropped = storedSong(1)
        val current = storedSong(2)
        Config.setDownloadedMixSongIds("mix:WEEK", listOf(dropped.id))

        val manager = createManager(UnconfinedDispatchers)
        manager.downloadMix("mix:WEEK", listOf(current))
        runBlocking { manager.cleanup() }

        assertEquals(mapOf("mix:WEEK" to listOf(current.id)), Config.downloadedMixSongIds.value)
        assertTrue(isKept(current))
        assertTrue(isDeleted(dropped))
    }

    @Test
    fun removingAMixDeletesSongsNoOtherMixHolds() {
        connectLibrary()
        val onlyHere = storedSong(1)
        val shared = storedSong(2)
        Config.setDownloadedMixSongIds("mix:WEEK", listOf(onlyHere.id, shared.id))
        Config.setDownloadedMixSongIds("mood:chill", listOf(shared.id))

        val manager = createManager(UnconfinedDispatchers)
        manager.removeMix("mix:WEEK")
        runBlocking { manager.cleanup() }

        assertEquals(mapOf("mood:chill" to listOf(shared.id)), Config.downloadedMixSongIds.value)
        assertTrue(isDeleted(onlyHere))
        assertTrue(isKept(shared))
        assertFalse(runBlocking { manager.isMixDownloaded("mix:WEEK").first() })
        assertTrue(runBlocking { manager.isMixDownloaded("mood:chill").first() })
    }

    private fun lyricsOf(song: UserSong, plain: String) = LyricsCandidate(
        id = randomPlatformUUID(),
        songId = song.id,
        provider = LyricsProviderInfo("lrclib", "LRCLIB", null, enabled = true, priority = 0),
        syncLevel = LyricsSyncLevel.PLAIN,
        instrumental = false,
        match = LyricsMatch.VERIFIED,
        durationGapSec = null,
        rank = 1,
        preferred = false,
        fetchedAt = Date(0L),
        plain = plain
    )

    private fun change(id: PlatformUUID, aspect: EntityChangeAspect = EntityChangeAspect.DATA, kind: EntityChangeKind = EntityChangeKind.UPDATED) =
        EntityChange(EntityType.SONG, id, aspect, kind, changedAt = 200L)

    private fun connect() {
        serverReachable.value = true
        connectionState.value = RpcServiceManager.ConnectionState.Authenticated
        scheduler.runCurrent()
    }

    private fun downloadedSongs(vararg songs: UserSong) {
        coEvery { libraryRepository.getSongs(false) } returns songs.toList()
        coEvery { entityChangeService.getWindow() } returns EntityChangeWindow(serverTime = 500L, availableSince = 50L)
    }

    @Test
    fun aCompletedDownloadStoresTheDefaultLyricsOfTheSong() {
        val song = song(fileSize = 8)
        val lyrics = lyricsOf(song, "first line")
        coEvery { lyricsService.getLyrics(song.id, null) } returns lyrics

        download(song, flowOf(ByteArray(8)))

        coVerifyOrder {
            libraryRepository.saveSongMetadata(song, false)
            offlineLyricsStore.saveLyrics(song.id, lyrics)
        }
    }

    @Test
    fun aDownloadWhoseLyricsCannotBeFetchedIsStillSavedOnce() {
        val song = song(fileSize = 8)
        coEvery { lyricsService.getLyrics(song.id, null) } throws IOException("lyrics unavailable")

        val manager = download(song, flowOf(ByteArray(8)))

        assertEquals(8L, File(storage.getInternalSongPath(song)).length())
        verify(exactly = 1) { downloadService.streamSong(song.id, any(), any()) }
        coVerify(exactly = 1) { libraryRepository.saveSongMetadata(song, false) }
        coVerify(exactly = 0) { offlineLyricsStore.saveLyrics(any(), any()) }
        coVerify(exactly = 0) { offlineLyricsStore.deleteLyrics(any()) }
        verify(exactly = 0) { snackbarManager.showSnackbar(any(), any(), any(), any()) }
        assertEquals(emptyList(), manager.queue.value)
    }

    @Test
    fun lyricsOfDownloadedSongsThatChangedAreFetchedAgain() {
        val changed = song(fileSize = 8)
        val liked = song(fileSize = 8)
        val elsewhere = randomPlatformUUID()
        val lyrics = lyricsOf(changed, "new line")
        downloadedSongs(changed, liked)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf(
            change(changed.id),
            change(liked.id, aspect = EntityChangeAspect.LIKE),
            change(elsewhere)
        )
        coEvery { lyricsService.getLyrics(changed.id, null) } returns lyrics

        createManager()
        connect()

        coVerify(exactly = 1) { offlineLyricsStore.saveLyrics(changed.id, lyrics) }
        coVerify(exactly = 0) { lyricsService.getLyrics(liked.id, any()) }
        coVerify(exactly = 0) { lyricsService.getLyrics(elsewhere, any()) }
        coVerify(exactly = 1) { lyricsRepository.invalidate(changed.id) }
        coVerify(exactly = 1) { lyricsRepository.invalidate(elsewhere) }
        coVerify(exactly = 0) { lyricsRepository.invalidate(liked.id) }
        assertEquals(500L, settings.getOrNull(SettingKey.SongChangesSince))
    }

    @Test
    fun lyricsRemovedOnTheServerAreDeletedFromTheStore() {
        val changed = song(fileSize = 8)
        downloadedSongs(changed)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf(change(changed.id))

        createManager()
        connect()

        coVerify(exactly = 1) { offlineLyricsStore.deleteLyrics(changed.id) }
        coVerify(exactly = 0) { offlineLyricsStore.saveLyrics(any(), any()) }
    }

    @Test
    fun aDeletedSongIsLeftToTheCleanup() {
        val deleted = song(fileSize = 8)
        downloadedSongs(deleted)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf(change(deleted.id, kind = EntityChangeKind.DELETED))

        createManager()
        connect()

        coVerify(exactly = 0) { lyricsService.getLyrics(deleted.id, any()) }
        coVerify(exactly = 1) { lyricsRepository.invalidate(deleted.id) }
        assertEquals(500L, settings.getOrNull(SettingKey.SongChangesSince))
    }

    @Test
    fun aFailedRefetchKeepsTheStoredLyricsAndPullsTheChangeAgainAfterAReconnect() {
        val changed = song(fileSize = 8)
        downloadedSongs(changed)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf(change(changed.id))
        coEvery { lyricsService.getLyrics(changed.id, null) } throws IOException("connection lost")

        createManager()
        connect()

        coVerify(exactly = 1) { lyricsService.getLyrics(changed.id, null) }
        coVerify(exactly = 0) { offlineLyricsStore.saveLyrics(any(), any()) }
        coVerify(exactly = 0) { offlineLyricsStore.deleteLyrics(any()) }
        assertEquals(100L, settings.getOrNull(SettingKey.SongChangesSince))

        serverReachable.value = false
        scheduler.runCurrent()
        serverReachable.value = true
        scheduler.runCurrent()

        verify(exactly = 2) { entityChangeService.allChanges(100L) }
    }

    @Test
    fun withoutAPreviousPullTheLyricsOfEveryDownloadedSongAreFetched() {
        val first = song(fileSize = 8)
        val second = song(fileSize = 8)
        downloadedSongs(first, second)
        val firstLyrics = lyricsOf(first, "first")
        val secondLyrics = lyricsOf(second, "second")
        coEvery { lyricsService.getLyrics(first.id, null) } returns firstLyrics
        coEvery { lyricsService.getLyrics(second.id, null) } returns secondLyrics

        createManager()
        connect()

        coVerify(exactly = 1) { offlineLyricsStore.saveLyrics(first.id, firstLyrics) }
        coVerify(exactly = 1) { offlineLyricsStore.saveLyrics(second.id, secondLyrics) }
        verify(exactly = 0) { entityChangeService.allChanges(any()) }
        assertEquals(500L, settings.getOrNull(SettingKey.SongChangesSince))
    }

    @Test
    fun aPullOlderThanTheRecordedChangesFetchesEveryDownloadedSong() {
        val song = song(fileSize = 8)
        downloadedSongs(song)
        settings.put(SettingKey.SongChangesSince, 10L)
        val lyrics = lyricsOf(song, "line")
        coEvery { lyricsService.getLyrics(song.id, null) } returns lyrics

        createManager()
        connect()

        coVerify(exactly = 1) { offlineLyricsStore.saveLyrics(song.id, lyrics) }
        verify(exactly = 0) { entityChangeService.allChanges(any()) }
    }

    @Test
    fun aReconnectPullsFromTheTimeOfThePreviousPull() {
        val changed = song(fileSize = 8)
        downloadedSongs(changed)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf(change(changed.id))
        every { entityChangeService.allChanges(500L) } returns flowOf()

        createManager()
        connect()
        coEvery { entityChangeService.getWindow() } returns EntityChangeWindow(serverTime = 900L, availableSince = 50L)
        serverReachable.value = false
        scheduler.runCurrent()
        serverReachable.value = true
        scheduler.runCurrent()

        verify(exactly = 1) { entityChangeService.allChanges(100L) }
        verify(exactly = 1) { entityChangeService.allChanges(500L) }
        assertEquals(900L, settings.getOrNull(SettingKey.SongChangesSince))
    }

    @Test
    fun aPullWithoutChangesTouchesNoLyrics() {
        val song = song(fileSize = 8)
        downloadedSongs(song)
        settings.put(SettingKey.SongChangesSince, 100L)
        every { entityChangeService.allChanges(100L) } returns flowOf()

        createManager()
        connect()
        scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { lyricsService.getLyrics(any(), any()) }
        coVerify(exactly = 0) { lyricsRepository.invalidate(any()) }
        assertEquals(500L, settings.getOrNull(SettingKey.SongChangesSince))
    }

    @Test
    fun nothingIsPulledWhileTheServerIsUnreachable() {
        downloadedSongs()
        connectionState.value = RpcServiceManager.ConnectionState.Authenticated

        createManager()
        scheduler.runCurrent()

        coVerify(exactly = 0) { entityChangeService.getWindow() }
    }
}
