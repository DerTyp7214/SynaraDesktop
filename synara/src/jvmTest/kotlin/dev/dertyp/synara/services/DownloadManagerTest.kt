package dev.dertyp.synara.services

import com.russhwolf.settings.Settings
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.BaseSong
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserProfile
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.synara.db.LibraryRepository
import dev.dertyp.synara.db.SavedLibraryIds
import dev.dertyp.synara.db.UserRepository
import dev.dertyp.synara.rpc.RpcServiceManager
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.download_failed
import java.io.File
import java.io.IOException
import java.nio.file.Files
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
    private val rpcManager = mockk<RpcServiceManager>(relaxed = true) {
        every { getService(ISongService::class, any()) } returns downloadService
    }
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

    @AfterTest
    fun tearDown() {
        manager?.stop()
        scheduler.advanceUntilIdle()
        root.deleteRecursively()
    }

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

    private fun createManager(): DownloadManager = DownloadManager(
        songService = songService,
        albumService = mockk(),
        artistService = mockk(),
        playlistService = playlistService,
        userService = userService,
        userRepository = userRepository,
        storageService = storage,
        libraryRepository = libraryRepository,
        settings = mockk<Settings>(relaxed = true),
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
}
