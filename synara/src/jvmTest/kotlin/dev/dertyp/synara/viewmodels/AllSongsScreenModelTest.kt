package dev.dertyp.synara.viewmodels

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.SongTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
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

class AllSongsScreenModelTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val songService = mockk<ISongService>()
    private val playerModel = mockk<PlayerModel>(relaxed = true)
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun createModel(total: Int = 0): AllSongsScreenModel {
        coEvery { songService.allSongs(any(), any(), any(), any(), any(), any(), any()) } returns
            PaginatedResponse(data = emptyList<UserSong>(), total = total)
        return AllSongsScreenModel(rpcServiceManager, songService, SongCache(), playerModel, UnconfinedDispatchers)
    }

    private fun AllSongsScreenModel.success(): AllSongsScreenModel.AllSongsState.Success =
        assertIs<AllSongsScreenModel.AllSongsState.Success>(state.value)

    @Test
    fun cycleFilterGoesOffIncludeExcludeOff() {
        val start = emptyList<SongTag>() to emptyList<SongTag>()
        val included = cycleFilter(SongTag.B_24, start.first, start.second)
        assertEquals(listOf(SongTag.B_24) to emptyList(), included)
        assertEquals(TagFilterState.INCLUDE, filterStateOf(SongTag.B_24, included.first, included.second))

        val excluded = cycleFilter(SongTag.B_24, included.first, included.second)
        assertEquals(emptyList<SongTag>() to listOf(SongTag.B_24), excluded)
        assertEquals(TagFilterState.EXCLUDE, filterStateOf(SongTag.B_24, excluded.first, excluded.second))

        val off = cycleFilter(SongTag.B_24, excluded.first, excluded.second)
        assertEquals(start, off)
        assertEquals(TagFilterState.OFF, filterStateOf(SongTag.B_24, off.first, off.second))
    }

    @Test
    fun cycleFilterLeavesOtherEntriesAlone() {
        val result = cycleFilter(SongTag.Q_96, listOf(SongTag.HAS_LYRICS), listOf(SongTag.B_16))
        assertEquals(listOf(SongTag.HAS_LYRICS, SongTag.Q_96) to listOf(SongTag.B_16), result)
    }

    @Test
    fun cyclingTagsUpdatesStateAndReloadsWithFullFilter() {
        val model = createModel()
        coVerify(exactly = 1) { songService.allSongs(0, model.pageSize, true, emptyList(), emptyList(), emptyList(), emptyList()) }

        model.cycleTag(SongTag.B_24)
        assertEquals(TagFilterState.INCLUDE, model.success().stateOf(SongTag.B_24))
        model.cycleTag(SongTag.B_24)
        model.cycleTag(SongTag.HAS_LYRICS)
        model.cycleTitleTag(TitleTagKind.LIVE)
        model.cycleTitleTag(TitleTagKind.REMIX)
        model.cycleTitleTag(TitleTagKind.REMIX)

        val state = model.success()
        assertEquals(listOf(SongTag.HAS_LYRICS), state.tags)
        assertEquals(listOf(SongTag.B_24), state.excludeTags)
        assertEquals(listOf(TitleTagKind.LIVE), state.titleTags)
        assertEquals(listOf(TitleTagKind.REMIX), state.excludeTitleTags)
        assertEquals(TagFilterState.EXCLUDE, state.stateOf(SongTag.B_24))
        assertEquals(TagFilterState.INCLUDE, state.stateOf(TitleTagKind.LIVE))
        assertEquals(TagFilterState.OFF, state.stateOf(TitleTagKind.MIX))

        coVerify(exactly = 1) {
            songService.allSongs(
                0,
                model.pageSize,
                true,
                listOf(SongTag.HAS_LYRICS),
                listOf(SongTag.B_24),
                listOf(TitleTagKind.LIVE),
                listOf(TitleTagKind.REMIX)
            )
        }
    }

    @Test
    fun pagingAndRefreshPassTheFullFilter() {
        val model = createModel(total = 1000)
        model.cycleTag(SongTag.Q_192)
        model.cycleTag(SongTag.Q_192)
        model.cycleTitleTag(TitleTagKind.MIX)

        model.loadPage(2)
        coVerify(exactly = 1) {
            songService.allSongs(2, model.pageSize, true, emptyList(), listOf(SongTag.Q_192), listOf(TitleTagKind.MIX), emptyList())
        }

        model.refresh()
        coVerify(exactly = 2) {
            songService.allSongs(0, model.pageSize, true, emptyList(), listOf(SongTag.Q_192), listOf(TitleTagKind.MIX), emptyList())
        }
        assertEquals(listOf(SongTag.Q_192), model.success().excludeTags)
    }

    @Test
    fun playAllAndPlaySongCarryTheFullFilter() {
        val model = createModel()
        model.cycleTag(SongTag.CUSTOM_UPLOAD)
        model.cycleTag(SongTag.B_16)
        model.cycleTag(SongTag.B_16)
        model.cycleTitleTag(TitleTagKind.COVER)
        model.cycleTitleTag(TitleTagKind.DEMO)
        model.cycleTitleTag(TitleTagKind.DEMO)

        val expected = PlaybackSource.AllSongs(
            tags = listOf(SongTag.CUSTOM_UPLOAD),
            excludeTags = listOf(SongTag.B_16),
            titleTags = listOf(TitleTagKind.COVER),
            excludeTitleTags = listOf(TitleTagKind.DEMO)
        )

        model.playAll()
        verify { playerModel.playQueue(PlaybackQueue(source = expected), 0) }

        model.playSong(mockk(), 7)
        verify { playerModel.playQueue(PlaybackQueue(source = expected), 7) }
    }
}
