package dev.dertyp.synara.viewmodels

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
}
