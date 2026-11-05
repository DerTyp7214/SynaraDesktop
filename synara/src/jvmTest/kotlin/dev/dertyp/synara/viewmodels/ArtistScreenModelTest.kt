package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.testSong
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtistScreenModelTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val artistId = randomPlatformUUID()
    private val credit = ArtistCredit(artistId, "Mac Miller", false)
    private val sharedCover = randomPlatformUUID()

    private val artistService = mockk<IArtistService> {
        coEvery { byId(any()) } returns null
        coEvery { topSongs(any(), any(), any()) } returns emptyList()
    }
    private val songService = mockk<ISongService> {
        coEvery { byArtist(any(), any(), any()) } returns PaginatedResponse(data = emptyList<UserSong>(), total = 0)
        coEvery { likedByArtist(any(), any(), any(), any()) } returns PaginatedResponse(data = emptyList<UserSong>(), total = 0)
    }
    private val albumService = mockk<IAlbumService>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun album(
        name: String,
        songCount: Int,
        coverId: PlatformUUID?,
        tags: List<TitleTag> = emptyList(),
        versions: List<Album> = emptyList()
    ) = Album(
        id = randomPlatformUUID(),
        name = name,
        artists = listOf(credit),
        songCount = songCount,
        releaseDate = null,
        totalDuration = 1000,
        coverId = coverId,
        tags = tags,
        versions = versions
    )

    private val deluxe = album("Swimming", 20, sharedCover, listOf(TitleTag(TitleTagKind.VERSION, "Deluxe Edition")))
    private val clean = album("Swimming", 13, randomPlatformUUID(), listOf(TitleTag(TitleTagKind.VERSION, "Clean")))
    private val swimming = album("Swimming", 13, sharedCover, versions = listOf(deluxe, clean))
    private val circles = album("Circles", 12, sharedCover)
    private val faces = album("Faces", 25, null)
    private val serverEntries = listOf(swimming, circles, faces)

    private fun stubAlbums() {
        coEvery { albumService.byArtist(any(), any(), any(), any(), any()) } returns
            PaginatedResponse(data = serverEntries, total = serverEntries.size)
    }

    @Test
    fun artistScreenAsksForTheExplicitMainEditionAndKeepsTheServerEntries() {
        stubAlbums()
        val model = ArtistScreenModel(
            artistId,
            rpcServiceManager,
            artistService,
            songService,
            albumService,
            SongCache(),
            mockk<PlayerModel>(relaxed = true),
            UnconfinedDispatchers
        )

        coVerify(exactly = 1) { albumService.byArtist(0, 10, artistId, false, true) }
        coVerify(exactly = 1) { albumService.byArtist(any(), any(), any(), any(), any()) }
        assertEquals(serverEntries, model.state.value.albums)
        assertEquals(listOf(deluxe, clean), model.state.value.albums.first().versions)
    }

    @Test
    fun artistScreenRefreshAsksAgainWithTheSameArguments() {
        stubAlbums()
        val model = ArtistScreenModel(
            artistId,
            rpcServiceManager,
            artistService,
            songService,
            albumService,
            SongCache(),
            mockk<PlayerModel>(relaxed = true),
            UnconfinedDispatchers
        )

        model.refresh()

        coVerify(exactly = 2) { albumService.byArtist(0, 10, artistId, false, true) }
        assertEquals(serverEntries, model.state.value.albums)
    }

    @Test
    fun artistAlbumsScreenAsksForTheExplicitMainEditionAndKeepsTheServerEntries() {
        stubAlbums()
        val model = ArtistAlbumsScreenModel(artistId, rpcServiceManager, artistService, albumService, UnconfinedDispatchers)

        coVerify(exactly = 1) { albumService.byArtist(0, Int.MAX_VALUE, artistId, false, true) }
        coVerify(exactly = 1) { albumService.byArtist(any(), any(), any(), any(), any()) }
        val state = assertIs<ArtistAlbumsScreenModel.ArtistAlbumsState.Success>(model.state.value)
        assertEquals(serverEntries, state.albums)
        assertEquals(listOf(deluxe, clean), state.albums.first().versions)
    }

    private val playerModel = mockk<PlayerModel>(relaxed = true)

    private val popular = listOf(
        ArtistTopSong(testSong(1), globalListenCount = 4_200, globalUserCount = 300, ownListenCount = 2),
        ArtistTopSong(testSong(1), globalListenCount = null, globalUserCount = null, ownListenCount = 9)
    )
    private val ownTop = popular.reversed()

    private fun createModel() = ArtistScreenModel(
        artistId,
        rpcServiceManager,
        artistService,
        songService,
        albumService,
        SongCache(),
        playerModel,
        UnconfinedDispatchers
    )

    @Test
    fun artistScreenLoadsTheTopFivePopularSongs() {
        stubAlbums()
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular

        val model = createModel()

        assertEquals(popular, model.state.value.popularSongs)
        assertEquals(ArtistTopSongOrder.GLOBAL_LISTENS, model.state.value.popularOrder)
        coVerify(exactly = 1) { artistService.topSongs(any(), any(), any()) }
    }

    @Test
    fun artistScreenToggleLoadsTheOwnTopAndARefreshKeepsTheToggle() {
        stubAlbums()
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.OWN_LISTENS) } returns ownTop
        val model = createModel()

        model.setPopularOrder(ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.popularOrder)
        assertEquals(ownTop, model.state.value.popularSongs)

        model.refresh()

        coVerify(exactly = 2) { artistService.topSongs(artistId, 5, ArtistTopSongOrder.OWN_LISTENS) }
        assertEquals(ownTop, model.state.value.popularSongs)
    }

    @Test
    fun artistScreenKeepsTheCountsOfTheShownListUntilTheToggledOneArrives() {
        stubAlbums()
        val ownTopResponse = CompletableDeferred<List<ArtistTopSong>>()
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.OWN_LISTENS) } coAnswers { ownTopResponse.await() }
        val model = createModel()

        model.setPopularOrder(ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.popularOrder)
        assertEquals(popular, model.state.value.popularSongs)
        assertEquals(ArtistTopSongOrder.GLOBAL_LISTENS, model.state.value.popularSongsOrder)

        ownTopResponse.complete(ownTop)

        assertEquals(ownTop, model.state.value.popularSongs)
        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.popularSongsOrder)
    }

    @Test
    fun artistScreenWithoutPopularSongsKeepsTheSectionEmpty() {
        stubAlbums()

        val model = createModel()

        assertEquals(emptyList(), model.state.value.popularSongs)
        assertEquals(serverEntries, model.state.value.albums)
    }

    @Test
    fun artistScreenStillLoadsWhenThePopularSongsFail() {
        stubAlbums()
        coEvery { artistService.topSongs(any(), any(), any()) } throws IOException("down")

        val model = createModel()

        assertEquals(emptyList(), model.state.value.popularSongs)
        assertEquals(serverEntries, model.state.value.albums)
        assertEquals(false, model.state.value.isLoading)
    }

    @Test
    fun artistScreenPlaysThePopularSongsFromTheClickedOne() {
        stubAlbums()
        coEvery { artistService.topSongs(artistId, 5, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        val model = createModel()
        val queue = slot<PlaybackQueue>()

        model.playPopular(1)

        verify(exactly = 1) { playerModel.playQueue(capture(queue), 1) }
        assertEquals(popular.map { it.song }, queue.captured.items.map { (it as QueueEntry.Explicit).song })
    }
}
