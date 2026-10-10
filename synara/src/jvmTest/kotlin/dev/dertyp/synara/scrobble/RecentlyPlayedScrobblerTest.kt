package dev.dertyp.synara.scrobble

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingRecentlyPlayedRepository : RecentlyPlayedRepository {
    val songs = mutableListOf<Triple<PlatformUUID, UserSong, Long>>()
    val albums = mutableListOf<Triple<PlatformUUID, Album, Long>>()
    val artists = mutableListOf<Triple<PlatformUUID, ArtistCredit, Long>>()

    override suspend fun insertSong(userId: PlatformUUID, song: UserSong, timestamp: Long) {
        songs += Triple(userId, song, timestamp)
    }

    override suspend fun insertAlbum(userId: PlatformUUID, album: Album, timestamp: Long) {
        albums += Triple(userId, album, timestamp)
    }

    override suspend fun insertArtist(userId: PlatformUUID, artist: ArtistCredit, timestamp: Long) {
        artists += Triple(userId, artist, timestamp)
    }

    override suspend fun getSongs(userId: PlatformUUID, limit: Long): List<UserSong> = emptyList()

    override suspend fun getAlbums(userId: PlatformUUID, limit: Long): List<Album> = emptyList()

    override suspend fun getArtists(userId: PlatformUUID, limit: Long): List<Artist> = emptyList()

    override fun getSongsFlow(userId: PlatformUUID, limit: Long): Flow<List<UserSong>> = emptyFlow()

    override fun getAlbumsFlow(userId: PlatformUUID, limit: Long): Flow<List<Album>> = emptyFlow()

    override fun getArtistsFlow(userId: PlatformUUID, limit: Long): Flow<List<Artist>> = emptyFlow()

    override suspend fun deleteAllSongs(userId: PlatformUUID) {}

    override suspend fun deleteAllAlbums(userId: PlatformUUID) {}

    override suspend fun deleteAllArtists(userId: PlatformUUID) {}
}

class RecentlyPlayedScrobblerTest {
    private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    private val userId = randomPlatformUUID()
    private val userFlow = MutableStateFlow<User?>(User(id = userId, username = "listener"))
    private val globalState = mockk<GlobalStateModel>()
    private val scrobblerService = mockk<ScrobblerService>(relaxed = true)
    private val repository = mockk<RecentlyPlayedRepository>(relaxed = true)
    private val song = testSong(0)

    private lateinit var scrobbler: RecentlyPlayedScrobbler

    @BeforeTest
    fun setUp() {
        every { globalState.user } returns userFlow
        startTestKoin(
            module {
                single { globalState }
                single<SynaraDispatchers> { TestDispatchers(testDispatcher) }
                single { scrobblerService }
            }
        )
        scrobbler = RecentlyPlayedScrobbler(repository)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
    }

    @Test
    fun triggeredListenIsInsertedForTheUser() = runTest(testDispatcher) {
        scrobbler.triggered(song, 1_700_000_000_000L)

        coVerify(exactly = 1) { repository.insertListen(userId, song, 1_700_000_000_000L) }
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)
    }

    @Test
    fun triggeredListenWithoutAUserIsNotInserted() = runTest(testDispatcher) {
        userFlow.value = null

        scrobbler.triggered(song, 1_700_000_000_000L)

        verify { repository wasNot Called }
        assertEquals(BaseScrobbler.ScrobbleStatus.IDLE, scrobbler.status.value)
    }

    @Test
    fun resetReturnsTheStatusToIdle() = runTest(testDispatcher) {
        scrobbler.triggered(song, 1_700_000_000_000L)
        assertEquals(BaseScrobbler.ScrobbleStatus.SCROBBLED, scrobbler.status.value)

        scrobbler.reset()

        assertEquals(BaseScrobbler.ScrobbleStatus.IDLE, scrobbler.status.value)
    }

    @Test
    fun insertedListenFansOutToTheSongItsAlbumAndEveryArtist() = runTest(testDispatcher) {
        val recording = RecordingRecentlyPlayedRepository()
        val album = testAlbum(0)
        val artists = listOf(testArtist(0), testArtist(1))
        val listened = testSong(0, album = album, artists = artists)

        recording.insertListen(userId, listened, 1_700_000_000_000L)

        assertEquals(listOf(Triple(userId, listened, 1_700_000_000_000L)), recording.songs)
        assertEquals(listOf(Triple(userId, album, 1_700_000_000_000L)), recording.albums)
        assertEquals(artists.map { Triple(userId, it, 1_700_000_000_000L) }, recording.artists)
    }

    @Test
    fun insertedListenWithoutAnAlbumInsertsNoAlbum() = runTest(testDispatcher) {
        val recording = RecordingRecentlyPlayedRepository()
        val listened = testSong(0, album = null)

        recording.insertListen(userId, listened, 1_700_000_000_000L)

        assertEquals(listOf(Triple(userId, listened, 1_700_000_000_000L)), recording.songs)
        assertEquals(emptyList(), recording.albums)
        assertEquals(emptyList(), recording.artists)
    }
}
