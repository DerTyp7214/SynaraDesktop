package dev.dertyp.synara.viewmodels

import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.UnconfinedDispatchers
import dev.dertyp.synara.testing.testSong
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistTopSongsScreenModelTest {

    private val artistId = randomPlatformUUID()
    private val artist = Artist(artistId, "Artist", false)
    private val artistService = mockk<IArtistService> {
        coEvery { byId(artistId) } returns artist
    }
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private val popular = listOf(
        ArtistTopSong(testSong(1), globalListenCount = 4_200, globalUserCount = 300, ownListenCount = 2),
        ArtistTopSong(testSong(2), globalListenCount = 900, globalUserCount = 80, ownListenCount = 7),
        ArtistTopSong(testSong(2), globalListenCount = null, globalUserCount = null, ownListenCount = 1)
    )
    private val ownTop = listOf(popular[1], popular[0], popular[2])

    private fun createModel(order: ArtistTopSongOrder = ArtistTopSongOrder.GLOBAL_LISTENS) = ArtistTopSongsScreenModel(
        artistId,
        order,
        rpcServiceManager,
        artistService,
        SongCache(),
        playerModel,
        UnconfinedDispatchers
    )

    private fun PlaybackQueue.songs(): List<UserSong> = items.map { (it as QueueEntry.Explicit).song }

    @Test
    fun theTopSongsLoadUpToAHundredInTheGivenOrderAndKeepSongsWithTheSameTitle() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular

        val model = createModel()

        val state = model.state.value
        assertEquals(artist, state.artist)
        assertEquals(popular, state.songs)
        assertEquals(ArtistTopSongOrder.GLOBAL_LISTENS, state.order)
        assertEquals(false, state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun theScreenOpensWithTheOrderItWasOpenedWith() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.OWN_LISTENS) } returns ownTop

        val model = createModel(ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(ownTop, model.state.value.songs)
        coVerify(exactly = 0) { artistService.topSongs(any(), any(), ArtistTopSongOrder.GLOBAL_LISTENS) }
    }

    @Test
    fun theToggleLoadsTheOtherOrder() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.OWN_LISTENS) } returns ownTop
        val model = createModel()

        model.setOrder(ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.order)
        assertEquals(ownTop, model.state.value.songs)

        model.setOrder(ArtistTopSongOrder.OWN_LISTENS)
        coVerify(exactly = 1) { artistService.topSongs(artistId, 100, ArtistTopSongOrder.OWN_LISTENS) }

        model.setOrder(ArtistTopSongOrder.GLOBAL_LISTENS)
        assertEquals(popular, model.state.value.songs)
    }

    @Test
    fun theCountsFollowTheShownListUntilTheToggledOneArrives() {
        val ownTopResponse = CompletableDeferred<List<ArtistTopSong>>()
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.OWN_LISTENS) } coAnswers { ownTopResponse.await() }
        val model = createModel()

        model.setOrder(ArtistTopSongOrder.OWN_LISTENS)

        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.order)
        assertEquals(popular, model.state.value.songs)
        assertEquals(ArtistTopSongOrder.GLOBAL_LISTENS, model.state.value.songsOrder)

        ownTopResponse.complete(ownTop)

        assertEquals(ownTop, model.state.value.songs)
        assertEquals(ArtistTopSongOrder.OWN_LISTENS, model.state.value.songsOrder)
    }

    @Test
    fun anEmptyResultIsLoadedWithoutAnError() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns emptyList()

        val model = createModel()

        assertEquals(emptyList(), model.state.value.songs)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun aFailedFirstLoadShowsTheErrorAndAFailedRefreshKeepsTheSongs() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } throws IOException("down")
        val failed = createModel()
        assertEquals("down", failed.state.value.error)

        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        val model = createModel()
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } throws IOException("down")
        model.refresh()

        assertEquals(popular, model.state.value.songs)
        assertNull(model.state.value.error)
    }

    @Test
    fun aSongPlaysTheListFromThatSong() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        val model = createModel()
        val queue = slot<PlaybackQueue>()

        model.play(2)

        verify(exactly = 1) { playerModel.playQueue(capture(queue), 2) }
        assertEquals(popular.map { it.song }, queue.captured.songs())
    }

    @Test
    fun shuffleTurnsOnShuffleAndPlaysTheList() {
        coEvery { artistService.topSongs(artistId, 100, ArtistTopSongOrder.GLOBAL_LISTENS) } returns popular
        val model = createModel()
        val queue = slot<PlaybackQueue>()

        model.shuffle()

        verify(exactly = 1) { playerModel.setShuffleMode(true) }
        verify(exactly = 1) { playerModel.playQueue(capture(queue), any()) }
        assertEquals(popular.map { it.song }, queue.captured.songs())
    }
}
