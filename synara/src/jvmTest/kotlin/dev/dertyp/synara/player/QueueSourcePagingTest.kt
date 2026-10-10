package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ISongService
import dev.dertyp.synara.testing.testSongs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSourcePagingTest {

    private class RecordingSource(private val songs: List<UserSong>) : BasePagedQueueSource("recording") {
        val requests = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
        val gate = MutableStateFlow<CompletableDeferred<Unit>?>(null)
        val failNext = MutableStateFlow(false)

        override suspend fun fetchPage(page: Int, pageSize: Int): PaginatedResponse<UserSong> {
            requests.update { it + (page to pageSize) }
            gate.value?.await()
            if (failNext.compareAndSet(expect = true, update = false)) throw IllegalStateException()
            val from = min(page * pageSize, songs.size)
            val to = min(from + pageSize, songs.size)
            return PaginatedResponse(data = songs.subList(from, to), page = page, total = songs.size, pageSize = pageSize)
        }

        override fun fetchIdFlow(): Flow<PlatformUUID> = songs.map { it.id }.asFlow()
    }

    private fun page(songs: List<UserSong>, page: Int, total: Int) =
        PaginatedResponse(data = songs, page = page, total = total, pageSize = 50)

    @Test
    fun listSourceReportsItsSize() = runTest {
        assertEquals(3, ListQueueSource("list", testSongs(3)).getSize())
        assertEquals(0, ListQueueSource("list", emptyList()).getSize())
    }

    @Test
    fun listSourceReturnsNullOutsideItsRange() = runTest {
        val songs = testSongs(3)
        val source = ListQueueSource("list", songs)

        assertEquals(songs[0], source.getSongAt(0))
        assertEquals(songs[2], source.getSongAt(2))
        assertNull(source.getSongAt(3))
        assertNull(source.getSongAt(-1))
    }

    @Test
    fun listSourceEmitsItsIdsInOrder() = runTest {
        val songs = testSongs(4)

        assertEquals(songs.map { it.id }, ListQueueSource("list", songs).getIdFlow().toList())
    }

    @Test
    fun getSizeFetchesASingleSongPageOnce() = runTest {
        val source = RecordingSource(testSongs(120))

        assertEquals(120, source.getSize())
        assertEquals(120, source.getSize())

        assertEquals(listOf(0 to 1), source.requests.value)
    }

    @Test
    fun getSongAtFetchesItsPageOnceAndServesThePageFromTheCache() = runTest {
        val songs = testSongs(120)
        val source = RecordingSource(songs)

        assertEquals(songs[73], source.getSongAt(73))
        assertEquals(songs[50], source.getSongAt(50))
        assertEquals(songs[99], source.getSongAt(99))
        assertEquals(songs[73], source.getSongAt(73))

        assertEquals(listOf(1 to 50), source.requests.value)
    }

    @Test
    fun pageFetchAlsoProvidesTheSize() = runTest {
        val source = RecordingSource(testSongs(120))

        source.getSongAt(10)

        assertEquals(120, source.getSize())
        assertEquals(listOf(0 to 50), source.requests.value)
    }

    @Test
    fun concurrentCallsOnOnePageShareOneFetch() = runTest {
        val songs = testSongs(120)
        val source = RecordingSource(songs)
        val gate = CompletableDeferred<Unit>()
        source.gate.value = gate

        val first = async { source.getSongAt(10) }
        val second = async { source.getSongAt(20) }
        val third = async { source.getSongAt(49) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(songs[10], first.await())
        assertEquals(songs[20], second.await())
        assertEquals(songs[49], third.await())
        assertEquals(listOf(0 to 50), source.requests.value)
    }

    @Test
    fun indexPastTheEndReturnsNull() = runTest {
        val songs = testSongs(60)
        val source = RecordingSource(songs)

        assertEquals(songs[59], source.getSongAt(59))
        assertNull(source.getSongAt(60))
        assertNull(source.getSongAt(99))
        assertNull(source.getSongAt(200))

        assertEquals(listOf(1 to 50), source.requests.value)
    }

    @Test
    fun failedFetchIsFetchedAgainOnTheNextCall() = runTest {
        val songs = testSongs(120)
        val source = RecordingSource(songs)
        source.failNext.value = true

        assertFailsWith<IllegalStateException> { source.getSongAt(5) }
        assertEquals(songs[5], source.getSongAt(5))

        assertEquals(listOf(0 to 50, 0 to 50), source.requests.value)
    }

    @Test
    fun searchIdFlowPagesUntilTheTotalIsReached() = runTest {
        val songs = testSongs(120)
        val songService = mockk<ISongService>()
        coEvery { songService.rankedSearch(any(), 50, "query", true, false) } answers {
            val index = firstArg<Int>()
            page(songs.drop(index * 50).take(50), index, songs.size)
        }

        val ids = SearchQueueSource(songService, "query").getIdFlow().toList()

        assertEquals(songs.map { it.id }, ids)
        coVerify(exactly = 1) { songService.rankedSearch(0, 50, "query", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(1, 50, "query", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(2, 50, "query", true, false) }
        coVerify(exactly = 3) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun searchIdFlowStopsAtAnEmptyPage() = runTest {
        val songs = testSongs(50)
        val songService = mockk<ISongService>()
        coEvery { songService.rankedSearch(any(), 50, "query", true, false) } answers {
            val index = firstArg<Int>()
            page(if (index == 0) songs else emptyList(), index, 120)
        }

        val ids = SearchQueueSource(songService, "query").getIdFlow().toList()

        assertEquals(songs.map { it.id }, ids)
        coVerify(exactly = 1) { songService.rankedSearch(0, 50, "query", true, false) }
        coVerify(exactly = 1) { songService.rankedSearch(1, 50, "query", true, false) }
        coVerify(exactly = 2) { songService.rankedSearch(any(), any(), any(), any(), any()) }
    }

    @Test
    fun userPlaylistSourceUsesTheUserPlaylistEndpoints() = runTest {
        val playlistId = randomPlatformUUID()
        val songs = testSongs(2)
        val songService = mockk<ISongService>()
        coEvery { songService.byUserPlaylist(any(), any(), playlistId) } returns page(songs, 0, 2)
        every { songService.songIdsByUserPlaylist(playlistId) } returns flowOf(songs[0].id, songs[1].id)
        val source = PlaylistQueueSource(songService, playlistId, isUserPlaylist = true)

        assertEquals(2, source.getSize())
        assertEquals(songs[1], source.getSongAt(1))
        assertEquals(songs.map { it.id }, source.getIdFlow().toList())

        coVerify(exactly = 1) { songService.byUserPlaylist(0, 1, playlistId) }
        coVerify(exactly = 1) { songService.byUserPlaylist(0, 50, playlistId) }
        verify(exactly = 1) { songService.songIdsByUserPlaylist(playlistId) }
        coVerify(exactly = 0) { songService.byPlaylist(any(), any(), any()) }
        verify(exactly = 0) { songService.songIdsByPlaylist(any()) }
    }

    @Test
    fun globalPlaylistSourceUsesThePlaylistEndpoints() = runTest {
        val playlistId = randomPlatformUUID()
        val songs = testSongs(2)
        val songService = mockk<ISongService>()
        coEvery { songService.byPlaylist(any(), any(), playlistId) } returns page(songs, 0, 2)
        every { songService.songIdsByPlaylist(playlistId) } returns flowOf(songs[0].id, songs[1].id)
        val source = PlaylistQueueSource(songService, playlistId, isUserPlaylist = false)

        assertEquals(2, source.getSize())
        assertEquals(songs[1], source.getSongAt(1))
        assertEquals(songs.map { it.id }, source.getIdFlow().toList())

        coVerify(exactly = 1) { songService.byPlaylist(0, 1, playlistId) }
        coVerify(exactly = 1) { songService.byPlaylist(0, 50, playlistId) }
        verify(exactly = 1) { songService.songIdsByPlaylist(playlistId) }
        coVerify(exactly = 0) { songService.byUserPlaylist(any(), any(), any()) }
        verify(exactly = 0) { songService.songIdsByUserPlaylist(any()) }
    }
}
