package dev.dertyp.synara.ui.components.lyrics

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.logging.Logger
import dev.dertyp.nowAsPlatformDate
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.models.*
import dev.dertyp.synara.lyrics.LyricsRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsPickerModelTest {
    private val repository = mockk<LyricsRepository>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val songId = randomPlatformUUID()

    private fun TestScope.model() = LyricsPickerModel(songId, repository, logger, backgroundScope)

    private fun candidate(
        id: PlatformUUID = randomPlatformUUID(),
        rank: Int = 0,
        provider: String = "lrclib"
    ) = LyricsCandidate(
        id = id,
        songId = songId,
        provider = LyricsProviderInfo(id = provider, name = provider, enabled = true, priority = 0),
        syncLevel = LyricsSyncLevel.LINE,
        instrumental = false,
        match = LyricsMatch.PROVIDER,
        durationGapSec = 2,
        rank = rank,
        preferred = false,
        fetchedAt = nowAsPlatformDate(),
        plain = "line"
    )

    private fun user(vararg capabilities: UserCapability, admin: Boolean = false) = User(
        id = randomPlatformUUID(),
        username = "user",
        isAdmin = admin,
        capabilities = capabilities.toList()
    )

    @Test
    fun streamedCandidatesAreMergedByIdAndKeptInRankOrder() = runTest {
        val first = candidate(rank = 0)
        val second = candidate(rank = 2)
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(first, second), first.id)
        val stream = Channel<LyricsCandidate>(Channel.UNLIMITED)
        every { repository.search(songId) } returns stream.consumeAsFlow()
        val model = model()
        model.load()
        runCurrent()

        model.search()
        runCurrent()
        assertTrue(model.searching.value)

        val found = candidate(rank = 1, provider = "kugou")
        stream.send(found)
        runCurrent()
        assertEquals(listOf(first, found, second), model.candidates.value)

        val updatedSecond = second.copy(rank = 3, match = LyricsMatch.VERIFIED)
        stream.send(updatedSecond)
        runCurrent()
        assertEquals(listOf(first, found, updatedSecond), model.candidates.value)

        stream.close()
        runCurrent()
        assertFalse(model.searching.value)
    }

    @Test
    fun aSecondSearchWhileOneRunsIsIgnored() = runTest {
        every { repository.search(songId) } returns Channel<LyricsCandidate>().consumeAsFlow()
        val model = model()

        model.search()
        runCurrent()
        model.search()
        runCurrent()

        verify(exactly = 1) { repository.search(songId) }
    }

    @Test
    fun loadingAgainKeepsCandidatesThatWereStreamedIn() = runTest {
        val stored = candidate(rank = 0)
        val streamed = candidate(rank = 1)
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(stored), stored.id)
        every { repository.search(songId) } returns flowOf(streamed)
        val model = model()

        model.search()
        runCurrent()
        model.load()
        runCurrent()

        assertEquals(listOf(stored, streamed), model.candidates.value)
    }

    @Test
    fun selectingTheDefaultClearsThePick() = runTest {
        val default = candidate()
        val other = candidate(rank = 1)
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(default, other), default.id)
        val model = model()
        model.load()
        runCurrent()

        model.select(other.id)
        model.select(default.id)

        verifyOrder {
            repository.pick(songId, other.id)
            repository.pick(songId, null)
        }
    }

    @Test
    fun makeDefaultIsOfferedOnlyToUsersWhoCanEdit() = runTest {
        val default = candidate()
        val other = candidate(rank = 1)
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(default, other), default.id)
        val model = model()
        model.load()
        runCurrent()

        assertFalse(model.makeDefaultOffered(null, other.id))
        assertFalse(model.makeDefaultOffered(user(), other.id))
        assertFalse(model.makeDefaultOffered(user(UserCapability.DELETE, UserCapability.IMPORT), other.id))
        assertTrue(model.makeDefaultOffered(user(UserCapability.EDIT), other.id))
        assertTrue(model.makeDefaultOffered(user(admin = true), other.id))
    }

    @Test
    fun makeDefaultIsNotOfferedForTheCurrentDefault() = runTest {
        val default = candidate()
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(default), default.id)
        val model = model()
        model.load()
        runCurrent()

        assertFalse(model.makeDefaultOffered(user(UserCapability.EDIT), default.id))
        assertFalse(model.makeDefaultOffered(user(UserCapability.EDIT), null))
    }

    @Test
    fun makingADefaultStoresItAndShowsTheNewDefault() = runTest {
        val default = candidate()
        val other = candidate(rank = 1)
        coEvery { repository.candidates(songId) } returns LyricsSearchResult(listOf(default, other), default.id)
        coEvery { repository.makeDefault(songId, other.id) } returns
            LyricsSearchResult(listOf(other.copy(rank = 0, preferred = true), default.copy(rank = 1)), other.id)
        val model = model()
        model.load()
        runCurrent()

        model.makeDefault(other.id)
        runCurrent()

        assertEquals(other.id, model.defaultCandidateId.value)
        assertEquals(listOf(other.id, default.id), model.candidates.value.map { it.id })
        coVerify { repository.makeDefault(songId, other.id) }
        verify { repository.pick(songId, null) }
    }
}
