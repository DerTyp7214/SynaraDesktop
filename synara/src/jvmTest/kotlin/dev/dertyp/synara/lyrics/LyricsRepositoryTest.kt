package dev.dertyp.synara.lyrics

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.logging.Logger
import dev.dertyp.nowAsPlatformDate
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.models.*
import dev.dertyp.synara.db.OfflineLyricsStore
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.ui.components.lyrics.LyricsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {
    private val service = mockk<ILyricsCandidateService>()
    private val offlineStore = mockk<OfflineLyricsStore>()
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val logger = mockk<Logger>(relaxed = true)
    private val reachable = MutableStateFlow(true)
    private val connection = MutableStateFlow(ConnectionState.Authenticated)
    private val songId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection
        coEvery { offlineStore.getLyrics(any()) } returns null
    }

    private fun repository() = LyricsRepository(service, offlineStore, rpcServiceManager, logger)

    private fun candidate(
        song: PlatformUUID = songId,
        id: PlatformUUID = randomPlatformUUID(),
        plain: String = "line"
    ) = LyricsCandidate(
        id = id,
        songId = song,
        provider = LyricsProviderInfo(id = "lrclib", name = "LRCLIB", enabled = true, priority = 0),
        syncLevel = LyricsSyncLevel.PLAIN,
        instrumental = false,
        match = LyricsMatch.VERIFIED,
        durationGapSec = 0,
        rank = 0,
        preferred = false,
        fetchedAt = nowAsPlatformDate(),
        plain = plain
    )

    private fun info(defaultCandidateId: PlatformUUID?) = SongLyricsInfo(
        available = defaultCandidateId != null,
        syncLevel = LyricsSyncLevel.PLAIN,
        levels = setOf(LyricsSyncLevel.PLAIN),
        defaultCandidateId = defaultCandidateId
    )

    private fun LyricsState.candidate() = (this as LyricsState.Loaded).candidate

    private fun TestScope.collectStates(flow: Flow<LyricsState>): MutableList<LyricsState> {
        val states = mutableListOf<LyricsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { states += it } }
        runCurrent()
        return states
    }

    @Test
    fun aSecondRequestIsServedFromTheCache() = runTest {
        val default = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        val repository = repository()

        assertEquals(default, repository.lyricsFor(songId).candidate())
        assertEquals(default, repository.lyricsFor(songId).candidate())

        coVerify(exactly = 1) { service.getLyrics(songId, null) }
    }

    @Test
    fun aSongWithoutLyricsIsCachedAsEmpty() = runTest {
        coEvery { service.getLyrics(songId, null) } returns null
        val repository = repository()

        assertEquals(LyricsState.Empty, repository.lyricsFor(songId))
        assertEquals(LyricsState.Empty, repository.lyricsFor(songId))

        coVerify(exactly = 1) { service.getLyrics(songId, null) }
    }

    @Test
    fun thePickedCandidateIsCachedApartFromTheDefault() = runTest {
        val default = candidate()
        val other = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        coEvery { service.getLyrics(songId, other.id) } returns other
        val repository = repository()

        assertEquals(default, repository.lyricsFor(songId).candidate())
        assertEquals(other, repository.lyricsFor(songId, other.id).candidate())
        assertEquals(default, repository.lyricsFor(songId).candidate())

        coVerify(exactly = 1) { service.getLyrics(songId, null) }
        coVerify(exactly = 1) { service.getLyrics(songId, other.id) }
    }

    @Test
    fun theLeastRecentlyUsedSongIsEvictedPastTheCacheSize() = runTest {
        val songs = List(LyricsRepository.CACHE_SIZE + 1) { randomPlatformUUID() }
        songs.forEach { id -> coEvery { service.getLyrics(id, null) } returns candidate(song = id) }
        val repository = repository()

        songs.take(LyricsRepository.CACHE_SIZE).forEach { repository.lyricsFor(it) }
        repository.lyricsFor(songs[0])
        repository.lyricsFor(songs.last())
        repository.lyricsFor(songs[0])
        repository.lyricsFor(songs[1])

        coVerify(exactly = 1) { service.getLyrics(songs[0], null) }
        coVerify(exactly = 2) { service.getLyrics(songs[1], null) }
    }

    @Test
    fun invalidatingASongReloadsItWithoutShowingLoadingAgain() = runTest {
        val first = candidate(plain = "old")
        val second = candidate(plain = "new")
        coEvery { service.getLyrics(songId, null) } returnsMany listOf(first, second)
        val repository = repository()

        val states = collectStates(repository.lyricsState(songId))
        repository.invalidate(songId)
        runCurrent()

        assertEquals(3, states.size)
        assertEquals(LyricsState.Loading, states[0])
        assertEquals(first, states[1].candidate())
        assertEquals(second, states[2].candidate())
    }

    @Test
    fun invalidatingAnotherSongLeavesTheCacheAlone() = runTest {
        coEvery { service.getLyrics(songId, null) } returns candidate()
        val repository = repository()

        repository.lyricsFor(songId)
        repository.invalidate(randomPlatformUUID())
        repository.lyricsFor(songId)

        coVerify(exactly = 1) { service.getLyrics(songId, null) }
    }

    @Test
    fun aSongWhoseDefaultCandidateChangedIsLoadedAgain() = runTest {
        val old = candidate()
        val new = candidate()
        coEvery { service.getLyrics(songId, null) } returnsMany listOf(old, new)
        val repository = repository()
        repository.lyricsFor(songId)

        val states = collectStates(repository.lyricsState(songId, info(new.id)))

        assertEquals(new, states.last().candidate())
        coVerify(exactly = 2) { service.getLyrics(songId, null) }
    }

    @Test
    fun aSongWhoseDefaultCandidateIsUnchangedComesFromTheCache() = runTest {
        val default = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        val repository = repository()
        repository.lyricsFor(songId)

        val states = collectStates(repository.lyricsState(songId, info(default.id)))

        assertEquals(default, states.single().candidate())
        coVerify(exactly = 1) { service.getLyrics(songId, null) }
    }

    @Test
    fun anUnreachableServerFallsBackToTheOfflineStore() = runTest {
        val stored = candidate()
        reachable.value = false
        coEvery { offlineStore.getLyrics(songId) } returns stored
        val repository = repository()

        assertEquals(stored, repository.lyricsFor(songId).candidate())

        coVerify(exactly = 0) { service.getLyrics(any(), any()) }
    }

    @Test
    fun aFailingServerFallsBackToTheOfflineStore() = runTest {
        val stored = candidate()
        coEvery { service.getLyrics(songId, null) } throws IllegalStateException("closed")
        coEvery { offlineStore.getLyrics(songId) } returns stored
        val repository = repository()

        assertEquals(stored, repository.lyricsFor(songId).candidate())
    }

    @Test
    fun offlineLyricsAreNotCachedSoTheServerIsAskedOnceItIsBack() = runTest {
        val stored = candidate(plain = "stored")
        val remote = candidate(plain = "remote")
        connection.value = ConnectionState.LoginRequired
        coEvery { offlineStore.getLyrics(songId) } returns stored
        coEvery { service.getLyrics(songId, null) } returns remote
        val repository = repository()

        val states = collectStates(repository.lyricsState(songId))
        assertEquals(stored, states.last().candidate())

        connection.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(remote, states.last().candidate())
    }

    @Test
    fun noServerAndNoOfflineCopyFails() = runTest {
        reachable.value = false
        val repository = repository()

        assertEquals(LyricsState.Failed, repository.lyricsFor(songId))
    }

    @Test
    fun aPickedCandidateIsLoadedForItsSong() = runTest {
        val default = candidate()
        val picked = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        coEvery { service.getLyrics(songId, picked.id) } returns picked
        val repository = repository()

        val states = collectStates(repository.lyricsState(songId))
        repository.pick(songId, picked.id)
        runCurrent()

        assertEquals(picked, states.last().candidate())
        assertEquals(picked, repository.shownLyrics(songId))
    }

    @Test
    fun thePickedCandidateIsResetWhenAnotherSongIsShown() = runTest {
        val otherSong = randomPlatformUUID()
        val default = candidate()
        val picked = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        coEvery { service.getLyrics(songId, picked.id) } returns picked
        coEvery { service.getLyrics(otherSong, null) } returns candidate(song = otherSong)
        val repository = repository()
        repository.pick(songId, picked.id)

        collectStates(repository.lyricsState(otherSong))
        assertNull(repository.picked.value)

        val states = collectStates(repository.lyricsState(songId))
        assertEquals(default, states.last().candidate())
        assertEquals(default, repository.shownLyrics(songId))
    }

    @Test
    fun aPickForAnotherSongDoesNotChangeTheShownLyrics() = runTest {
        val default = candidate()
        coEvery { service.getLyrics(songId, null) } returns default
        val repository = repository()
        val states = collectStates(repository.lyricsState(songId))

        repository.pick(randomPlatformUUID(), randomPlatformUUID())
        runCurrent()

        assertEquals(default, states.last().candidate())
        coVerify(exactly = 1) { service.getLyrics(any(), any()) }
    }

    @Test
    fun makingACandidateTheDefaultReloadsTheSong() = runTest {
        val old = candidate()
        val new = candidate()
        coEvery { service.getLyrics(songId, null) } returnsMany listOf(old, new)
        coEvery { service.setPreferredLyrics(songId, new.id) } returns LyricsSearchResult(listOf(old, new), new.id)
        val repository = repository()
        repository.lyricsFor(songId)

        repository.makeDefault(songId, new.id)

        assertEquals(new, repository.lyricsFor(songId).candidate())
        coVerify(exactly = 1) { service.setPreferredLyrics(songId, new.id) }
    }

    @Test
    fun aFinishedSearchReloadsTheSong() = runTest {
        val old = candidate()
        val found = candidate()
        coEvery { service.getLyrics(songId, null) } returnsMany listOf(old, found)
        every { service.streamLyrics(songId, null) } returns flowOf(found)
        val repository = repository()
        repository.lyricsFor(songId)

        assertEquals(listOf(found), repository.search(songId).toList())

        assertEquals(found, repository.lyricsFor(songId).candidate())
    }

    @Test
    fun candidatesAreListedWithoutContent() = runTest {
        val result = LyricsSearchResult(listOf(candidate()), null)
        coEvery { service.getLyricsCandidates(songId, false) } returns result
        val repository = repository()

        assertSame(result, repository.candidates(songId))
    }
}
