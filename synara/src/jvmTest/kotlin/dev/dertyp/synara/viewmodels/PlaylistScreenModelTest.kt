package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IImageService
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.testing.testSongs
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaylistScreenModelTest {

    private val playlistId = randomPlatformUUID()
    private val songCache = SongCache()
    private val playlistService = mockk<IPlaylistService>()
    private val userPlaylistService = mockk<IUserPlaylistService>()
    private val songService = mockk<ISongService>()
    private val imageService = mockk<IImageService>()
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun page(songs: List<UserSong>, hasNext: Boolean) =
        PaginatedResponse(data = songs, total = songs.size, hasNextPage = hasNext)

    private fun systemPlaylist(name: String = "Mix", imageId: PlatformUUID? = null, duration: Long = 1234L) =
        Playlist(id = playlistId, name = name, songs = emptyList(), totalDuration = duration, imageId = imageId)

    private fun userPlaylist(name: String = "Mine", imageId: PlatformUUID? = null, duration: Long = 1234L) =
        UserPlaylist(
            id = playlistId,
            name = name,
            songs = emptyList(),
            totalDuration = duration,
            imageId = imageId,
            creator = randomPlatformUUID(),
            description = ""
        )

    private fun stubSystem(first: PaginatedResponse<UserSong>, playlist: Playlist? = systemPlaylist()) {
        coEvery { playlistService.byId(playlistId) } returns playlist
        coEvery { songService.byPlaylist(0, 50, playlistId) } returns first
    }

    private fun stubUser(first: PaginatedResponse<UserSong>, playlist: UserPlaylist? = userPlaylist()) {
        coEvery { userPlaylistService.byId(playlistId) } returns playlist
        coEvery { songService.byUserPlaylist(0, 50, playlistId) } returns first
    }

    private fun createModel(
        isUserPlaylist: Boolean = false,
        dispatchers: SynaraDispatchers = UnconfinedDispatchers
    ) = PlaylistScreenModel(
        playlistId,
        isUserPlaylist,
        rpcServiceManager,
        playlistService,
        userPlaylistService,
        songService,
        imageService,
        songCache,
        playerModel,
        mockk<Logger>(relaxed = true),
        dispatchers
    )

    private fun PlaylistScreenModel.success() = assertIs<PlaylistScreenModel.PlaylistState.Success>(state.value)

    @Test
    fun aUserPlaylistLoadsThroughTheUserServicesOnly() {
        val songs = testSongs(2)
        stubUser(page(songs, hasNext = false))

        val model = createModel(isUserPlaylist = true)

        val state = model.success()
        assertEquals(songs, state.songs)
        assertEquals("Mine", state.name)
        assertEquals(true, state.isUserPlaylist)
        coVerify(exactly = 1) { userPlaylistService.byId(playlistId) }
        coVerify(exactly = 1) { songService.byUserPlaylist(0, 50, playlistId) }
        coVerify(exactly = 0) { playlistService.byId(any()) }
        coVerify(exactly = 0) { songService.byPlaylist(any(), any(), any()) }
    }

    @Test
    fun aSystemPlaylistLoadsThroughTheSystemServicesOnly() {
        val songs = testSongs(2)
        stubSystem(page(songs, hasNext = false))

        val model = createModel(isUserPlaylist = false)

        val state = model.success()
        assertEquals(songs, state.songs)
        assertEquals("Mix", state.name)
        assertEquals(false, state.isUserPlaylist)
        coVerify(exactly = 1) { playlistService.byId(playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(0, 50, playlistId) }
        coVerify(exactly = 0) { userPlaylistService.byId(any()) }
        coVerify(exactly = 0) { songService.byUserPlaylist(any(), any(), any()) }
    }

    @Test
    fun onlyTheFirstPageRequestsThePlaylistMetadata() {
        stubSystem(page(testSongs(2), hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } returns page(testSongs(1), hasNext = false)

        val model = createModel()
        model.loadNextPage()

        coVerify(exactly = 1) { playlistService.byId(playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(0, 50, playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(1, 50, playlistId) }
    }

    @Test
    fun loadingTheNextPageAppendsItAndKeepsNameImageAndDuration() {
        val imageId = randomPlatformUUID()
        val first = testSongs(2)
        val second = listOf(testSong(10))
        stubSystem(page(first, hasNext = true), systemPlaylist(name = "Keep", imageId = imageId, duration = 777L))
        coEvery { songService.byPlaylist(1, 50, playlistId) } returns page(second, hasNext = false)

        val model = createModel()
        assertEquals(true, model.success().hasNextPage)
        model.loadNextPage()

        val state = model.success()
        assertEquals(first + second, state.songs)
        assertEquals("Keep", state.name)
        assertEquals(imageId, state.imageId)
        assertEquals(777L, state.totalDuration)
        assertEquals(false, state.hasNextPage)
    }

    @Test
    fun loadingTheNextPageDoesNothingWhenThereIsNoFurtherPage() {
        stubSystem(page(testSongs(2), hasNext = false))

        val model = createModel()
        model.loadNextPage()

        coVerify(exactly = 1) { songService.byPlaylist(any(), any(), any()) }
        assertEquals(2, model.success().songs.size)
    }

    @Test
    fun aFailingFirstPageShowsTheErrorState() {
        coEvery { playlistService.byId(playlistId) } returns systemPlaylist()
        coEvery { songService.byPlaylist(0, 50, playlistId) } throws IOException("down")

        val model = createModel()

        assertIs<PlaylistScreenModel.PlaylistState.Error>(model.state.value)
    }

    @Test
    fun aFailingLaterPageKeepsTheLoadedPlaylist() {
        val first = testSongs(2)
        stubSystem(page(first, hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } throws IOException("down")

        val model = createModel()
        model.loadNextPage()

        assertEquals(first, model.success().songs)
    }

    @Test
    fun aContentChangeOfThisPlaylistReloadsTheFirstPageAndResetsPaging() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        stubSystem(page(first, hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } returns page(second, hasNext = true)

        val model = createModel()
        model.loadNextPage()
        assertEquals(first + second, model.success().songs)

        stubSystem(page(refreshed, hasNext = true))
        songCache.notifyPlaylistChanged(playlistId)

        assertEquals(refreshed, model.success().songs)
        model.loadNextPage()
        coVerify(exactly = 2) { songService.byPlaylist(1, 50, playlistId) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aContentChangeOfAnotherPlaylistChangesNothing() {
        val first = testSongs(2)
        stubSystem(page(first, hasNext = false))

        val model = createModel()
        songCache.notifyPlaylistChanged(randomPlatformUUID())

        coVerify(exactly = 1) { songService.byPlaylist(any(), any(), any()) }
        coVerify(exactly = 1) { playlistService.byId(any()) }
        assertEquals(first, model.success().songs)
    }

    @Test
    fun aFailedReloadKeepsTheLoadedPlaylist() {
        val first = testSongs(2)
        stubSystem(page(first, hasNext = false))

        val model = createModel()
        val before = model.success()
        coEvery { songService.byPlaylist(0, 50, playlistId) } throws IOException("down")
        songCache.notifyPlaylistChanged(playlistId)

        coVerify(exactly = 2) { songService.byPlaylist(0, 50, playlistId) }
        assertEquals(before, model.state.value)
    }

    @Test
    fun aSongUpdateReplacesTheSongInPlace() {
        val songs = testSongs(3)
        stubSystem(page(songs, hasNext = false))
        val model = createModel()

        val updated = songs[1].copy(title = "Renamed")
        runBlocking { songCache.put(updated) }

        assertEquals(listOf(songs[0], updated, songs[2]), model.success().songs)
    }

    @Test
    fun aSongUpdateForAnUnknownSongIsIgnored() {
        val songs = testSongs(3)
        stubSystem(page(songs, hasNext = false))
        val model = createModel()

        runBlocking { songCache.put(testSong(99)) }
        assertEquals(songs, model.success().songs)

        val updated = songs[2].copy(title = "Renamed")
        runBlocking { songCache.put(updated) }

        assertEquals(listOf(songs[0], songs[1], updated), model.success().songs)
    }

    @Test
    fun settingACoverOnASystemPlaylistDoesNothing() {
        stubSystem(page(testSongs(1), hasNext = false))
        val model = createModel(isUserPlaylist = false)

        model.setCover(byteArrayOf(1, 2, 3))

        coVerify(exactly = 0) { imageService.createImage(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.setPlaylistImage(any(), any()) }
        assertEquals(null, model.success().imageId)
    }

    @Test
    fun settingACoverOnAUserPlaylistStoresItAndUpdatesTheImageId() {
        val newImage = randomPlatformUUID()
        stubUser(page(testSongs(1), hasNext = false))
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { imageService.createImage(any(), any()) } returns newImage
        coEvery { userPlaylistService.setPlaylistImage(playlistId, newImage) } returns true
        val model = createModel(isUserPlaylist = true)

        model.setCover(bytes)

        coVerify(exactly = 1) { imageService.createImage(bytes, any()) }
        coVerify(exactly = 1) { imageService.createImage(any(), any()) }
        coVerify(exactly = 1) { userPlaylistService.setPlaylistImage(playlistId, newImage) }
        assertEquals(newImage, model.success().imageId)
    }

    @Test
    fun playingASongQueuesThePlaylistAtTheSongIndex() {
        val songs = testSongs(3)
        stubSystem(page(songs, hasNext = false))
        val model = createModel()

        model.playSong(songs[2])

        verify { playerModel.playQueue(PlaybackQueue(source = PlaybackSource.Playlist(playlistId)), 2) }
    }

    @Test
    fun playingASongThatIsNotListedStartsAtTheFirstEntry() {
        stubSystem(page(testSongs(3), hasNext = false))
        val model = createModel()

        model.playSong(testSong(99))

        verify { playerModel.playQueue(PlaybackQueue(source = PlaybackSource.Playlist(playlistId)), 0) }
    }

    @Test
    fun aPageFetchInFlightWhenAReloadCompletesFirstIsDropped() {
        val first = testSongs(2)
        val stalePage = listOf(testSong(10))
        val refreshed = listOf(testSong(20))
        stubSystem(page(first, hasNext = true))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.byPlaylist(1, 50, playlistId) } coAnswers { gate.await() }

        val model = createModel()
        model.loadNextPage()

        stubSystem(page(refreshed, hasNext = false))
        songCache.notifyPlaylistChanged(playlistId)
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(stalePage, hasNext = false))

        assertEquals(refreshed, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aUserPlaylistPageFetchInFlightWhenAReloadCompletesFirstIsDropped() {
        val first = testSongs(2)
        val stalePage = listOf(testSong(10))
        val refreshed = listOf(testSong(20))
        stubUser(page(first, hasNext = true))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.byUserPlaylist(1, 50, playlistId) } coAnswers { gate.await() }

        val model = createModel(isUserPlaylist = true)
        model.loadNextPage()
        assertEquals(first, model.success().songs)

        stubUser(page(refreshed, hasNext = false))
        songCache.notifyPlaylistChanged(playlistId)
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(stalePage, hasNext = false))

        assertEquals(refreshed, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aReloadThatFailsAfterTheFirstLoadSucceededKeepsTheFirstLoad() {
        val first = testSongs(2)
        val gates = List(2) { CompletableDeferred<PaginatedResponse<UserSong>>() }
        var calls = 0
        coEvery { playlistService.byId(playlistId) } returns systemPlaylist()
        coEvery { songService.byPlaylist(0, 50, playlistId) } coAnswers { gates[calls++].await() }
        val model = createModel()
        songCache.notifyPlaylistChanged(playlistId)
        coVerify(exactly = 2) { songService.byPlaylist(0, 50, playlistId) }

        gates[0].complete(page(first, hasNext = false))
        assertEquals(first, model.success().songs)
        gates[1].completeExceptionally(IOException("down"))

        val state = model.success()
        assertEquals(first, state.songs)
        assertEquals("Mix", state.name)
    }

    @Test
    fun aReloadThatFailsWithNothingLoadedShowsTheErrorState() {
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var calls = 0
        coEvery { playlistService.byId(playlistId) } returns systemPlaylist()
        coEvery { songService.byPlaylist(0, 50, playlistId) } coAnswers {
            if (calls++ == 0) throw IOException("down") else gate.await()
        }
        val model = createModel()
        assertIs<PlaylistScreenModel.PlaylistState.Error>(model.state.value)

        songCache.notifyPlaylistChanged(playlistId)
        gate.completeExceptionally(IOException("still down"))

        assertEquals("still down", assertIs<PlaylistScreenModel.PlaylistState.Error>(model.state.value).message)
    }

    @Test
    fun aPageRequestedWhileAReloadIsInFlightIsDroppedAndRequestedAgainOnTheFreshList() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        stubSystem(page(first, hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } returns page(second, hasNext = true)
        val model = createModel()
        model.loadNextPage()
        assertEquals(first + second, model.success().songs)

        val reloadGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        val pageGate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { songService.byPlaylist(0, 50, playlistId) } coAnswers { reloadGate.await() }
        coEvery { songService.byPlaylist(2, 50, playlistId) } coAnswers { pageGate.await() }
        songCache.notifyPlaylistChanged(playlistId)
        model.loadNextPage()
        coVerify(exactly = 1) { songService.byPlaylist(2, 50, playlistId) }
        reloadGate.complete(page(refreshed, hasNext = true))
        assertEquals(refreshed, model.success().songs)

        coVerify(exactly = 1) { songService.byPlaylist(1, 50, playlistId) }

        pageGate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.byPlaylist(1, 50, playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(2, 50, playlistId) }
        assertEquals(refreshed + second, model.success().songs)
    }

    @Test
    fun aPageDroppedByAReloadIsRequestedAgainWithoutAnotherScroll() {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val refreshed = listOf(testSong(20), testSong(21))
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        var pageOneCalls = 0
        stubSystem(page(first, hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } coAnswers {
            if (pageOneCalls++ == 0) gate.await() else page(second, hasNext = false)
        }
        val model = createModel()
        model.loadNextPage()
        stubSystem(page(refreshed, hasNext = true))
        songCache.notifyPlaylistChanged(playlistId)
        assertEquals(refreshed, model.success().songs)

        gate.complete(page(listOf(testSong(30)), hasNext = true))

        coVerify(exactly = 2) { songService.byPlaylist(1, 50, playlistId) }
        assertEquals(refreshed + second, model.success().songs)
        assertEquals(false, model.success().hasNextPage)
    }

    @Test
    fun aFailingReloadDuringTheFirstLoadKeepsTheFirstLoad() {
        val first = testSongs(2)
        val gate = CompletableDeferred<PaginatedResponse<UserSong>>()
        coEvery { playlistService.byId(playlistId) } returns systemPlaylist()
        coEvery { songService.byPlaylist(0, 50, playlistId) } coAnswers { gate.await() }
        val model = createModel()
        assertIs<PlaylistScreenModel.PlaylistState.Loading>(model.state.value)

        coEvery { songService.byPlaylist(0, 50, playlistId) } throws IOException("down")
        songCache.notifyPlaylistChanged(playlistId)
        gate.complete(page(first, hasNext = false))

        val state = model.success()
        assertEquals(first, state.songs)
        assertEquals("Mix", state.name)
    }

    @Test
    fun aPageRequestedTheMomentTheListChangesIsLoadedOnceThePageInProgressIsDone() = runTest {
        val first = testSongs(2)
        val second = listOf(testSong(10))
        val third = listOf(testSong(20))
        stubSystem(page(first, hasNext = true))
        coEvery { songService.byPlaylist(1, 50, playlistId) } returns page(second, hasNext = true)
        coEvery { songService.byPlaylist(2, 50, playlistId) } returns page(third, hasNext = false)
        val model = createModel(dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(first, model.success().songs)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { model.loadNextPage() } }
        runCurrent()

        assertEquals(first + second + third, model.success().songs)
        coVerify(exactly = 1) { songService.byPlaylist(1, 50, playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(2, 50, playlistId) }
    }
}
