package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.services.TimecodeTagServiceWrapper
import dev.dertyp.synara.testing.TestDispatchers
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimecodeTagStoreTest {
    private val service = mockk<TimecodeTagServiceWrapper>()
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val logger = mockk<Logger>(relaxed = true)
    private val songId = randomPlatformUUID()
    private val userId = randomPlatformUUID()

    @BeforeTest
    fun setUp() {
        coJustRun { rpcServiceManager.awaitAuthentication() }
    }

    private fun TestScope.createStore() = TimecodeTagStore(
        service,
        rpcServiceManager,
        logger,
        TestDispatchers(StandardTestDispatcher(testScheduler))
    )

    private fun tag(timestampMs: Long, song: PlatformUUID = songId) = TimecodeTag(
        id = randomPlatformUUID(),
        userId = userId,
        songId = song,
        type = TimecodeTagType.MARKER,
        timestampMs = timestampMs,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun twoEnsureLoadedCallsBeforeCompletionFetchTheTagsOnce() = runTest {
        val gate = CompletableDeferred<List<TimecodeTag>>()
        coEvery { service.getTags(songId) } coAnswers { gate.await() }
        val store = createStore()
        val loaded = listOf(tag(1_000))

        store.ensureLoaded(songId)
        store.ensureLoaded(songId)
        runCurrent()
        store.ensureLoaded(songId)
        runCurrent()
        gate.complete(loaded)
        runCurrent()
        store.ensureLoaded(songId)
        runCurrent()

        coVerify(exactly = 1) { service.getTags(songId) }
        assertEquals(loaded, store.cached(songId))
    }

    @Test
    fun tagsAreStoredSortedByTimestamp() = runTest {
        val early = tag(1_000)
        val middle = tag(5_000)
        val late = tag(9_000)
        coEvery { service.getTags(songId) } returns listOf(late, early, middle)
        val store = createStore()

        store.ensureLoaded(songId)
        runCurrent()

        assertEquals(listOf(early, middle, late), store.cached(songId))
        assertEquals(listOf(early, middle, late), store.tagsBySong.value[songId])
    }

    @Test
    fun createReloadsTheTagsOfTheSong() = runTest {
        val existing = tag(9_000)
        val created = tag(1_000)
        coEvery {
            service.createTag(songId, TimecodeTagType.CHAPTER, "Intro", 1_000, 2_000, TimecodeTagAction.SKIP, true)
        } returns created
        coEvery { service.getTags(songId) } returns listOf(existing, created)
        val store = createStore()

        val result = store.create(songId, TimecodeTagType.CHAPTER, "Intro", 1_000, 2_000, TimecodeTagAction.SKIP, true)

        assertEquals(created, result)
        coVerify(exactly = 1) {
            service.createTag(songId, TimecodeTagType.CHAPTER, "Intro", 1_000, 2_000, TimecodeTagAction.SKIP, true)
        }
        coVerify(exactly = 1) { service.getTags(songId) }
        assertEquals(listOf(created, existing), store.cached(songId))
    }

    @Test
    fun updateReloadsTheTagsOfTheSong() = runTest {
        val original = tag(1_000)
        val updated = original.copy(timestampMs = 4_000, type = TimecodeTagType.NOTE)
        coEvery {
            service.updateTag(original.id, TimecodeTagType.NOTE, "Note", 4_000, null, TimecodeTagAction.NONE, false)
        } returns updated
        coEvery { service.getTags(songId) } returns listOf(updated)
        val store = createStore()

        val result = store.update(original, TimecodeTagType.NOTE, "Note", 4_000, null, TimecodeTagAction.NONE, false)

        assertEquals(updated, result)
        coVerify(exactly = 1) {
            service.updateTag(original.id, TimecodeTagType.NOTE, "Note", 4_000, null, TimecodeTagAction.NONE, false)
        }
        coVerify(exactly = 1) { service.getTags(songId) }
        assertEquals(listOf(updated), store.cached(songId))
    }

    @Test
    fun deleteReloadsTheTagsOfTheSong() = runTest {
        val removed = tag(1_000)
        val remaining = tag(2_000)
        coEvery { service.getTags(songId) } returnsMany listOf(listOf(removed, remaining), listOf(remaining))
        coEvery { service.deleteTag(removed.id) } returns true
        val store = createStore()
        store.ensureLoaded(songId)
        runCurrent()
        assertEquals(listOf(removed, remaining), store.cached(songId))

        val deleted = store.delete(removed)

        assertTrue(deleted)
        coVerify(exactly = 1) { service.deleteTag(removed.id) }
        coVerify(exactly = 2) { service.getTags(songId) }
        assertEquals(listOf(remaining), store.cached(songId))
    }

    @Test
    fun refreshIfCachedLoadsOnlyCachedSongs() = runTest {
        val otherSongId = randomPlatformUUID()
        val first = tag(1_000)
        val second = tag(2_000)
        coEvery { service.getTags(songId) } returnsMany listOf(listOf(first), listOf(first, second))
        coEvery { service.getTags(otherSongId) } returns listOf(tag(3_000, otherSongId))
        val store = createStore()
        store.ensureLoaded(songId)
        runCurrent()

        store.refreshIfCached(otherSongId)
        store.refreshIfCached(songId)
        runCurrent()

        coVerify(exactly = 0) { service.getTags(otherSongId) }
        coVerify(exactly = 2) { service.getTags(songId) }
        assertNull(store.cached(otherSongId))
        assertEquals(listOf(first, second), store.cached(songId))
    }

    @Test
    fun observeYieldsTheSeedUntilTheTagsAreLoaded() = runTest {
        val gate = CompletableDeferred<List<TimecodeTag>>()
        coEvery { service.getTags(songId) } coAnswers { gate.await() }
        val store = createStore()
        val seed = listOf(tag(500))
        val early = tag(1_000)
        val late = tag(2_000)

        val observed = store.observe(songId, seed)
        assertEquals(seed, observed.value)
        val values = mutableListOf<List<TimecodeTag>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observed.toList(values) }
        runCurrent()
        assertEquals(listOf(seed), values)

        gate.complete(listOf(late, early))
        runCurrent()

        assertEquals(listOf(seed, listOf(early, late)), values)
        assertEquals(listOf(early, late), observed.value)
        coVerify(exactly = 1) { service.getTags(songId) }
    }

    @Test
    fun failedFirstLoadKeepsTheSeedAndIsRetriedByTheNextEnsureLoaded() = runTest {
        coEvery { service.getTags(songId) } throws IllegalStateException()
        val store = createStore()
        val seed = listOf(tag(500))
        val early = tag(1_000)
        val late = tag(2_000)

        val observed = store.observe(songId, seed)
        val values = mutableListOf<List<TimecodeTag>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observed.toList(values) }
        runCurrent()

        coVerify(exactly = 1) { service.getTags(songId) }
        assertEquals(listOf(seed), values)
        assertEquals(seed, observed.value)
        assertNull(store.cached(songId))

        coEvery { service.getTags(songId) } returns listOf(late, early)
        store.ensureLoaded(songId)
        runCurrent()

        coVerify(exactly = 2) { service.getTags(songId) }
        assertEquals(listOf(seed, listOf(early, late)), values)
        assertEquals(listOf(early, late), observed.value)
        assertEquals(listOf(early, late), store.cached(songId))
    }

    @Test
    fun failedReloadAfterCreateKeepsThePreviouslyLoadedTags() = runTest {
        val existing = tag(9_000)
        val created = tag(1_000)
        coEvery { service.getTags(songId) } returns listOf(existing)
        coEvery {
            service.createTag(songId, TimecodeTagType.CHAPTER, "Intro", 1_000, 2_000, TimecodeTagAction.SKIP, true)
        } returns created
        val store = createStore()
        store.ensureLoaded(songId)
        runCurrent()
        coEvery { service.getTags(songId) } throws IllegalStateException()

        val result = store.create(songId, TimecodeTagType.CHAPTER, "Intro", 1_000, 2_000, TimecodeTagAction.SKIP, true)

        assertEquals(created, result)
        coVerify(exactly = 2) { service.getTags(songId) }
        assertEquals(listOf(existing), store.cached(songId))
        assertEquals(listOf(existing), store.tagsBySong.value[songId])
    }

    @Test
    fun failedReloadAfterUpdateKeepsThePreviouslyLoadedTags() = runTest {
        val original = tag(1_000)
        val other = tag(2_000)
        val updated = original.copy(timestampMs = 4_000, type = TimecodeTagType.NOTE)
        coEvery { service.getTags(songId) } returns listOf(original, other)
        coEvery {
            service.updateTag(original.id, TimecodeTagType.NOTE, "Note", 4_000, null, TimecodeTagAction.NONE, false)
        } returns updated
        val store = createStore()
        store.ensureLoaded(songId)
        runCurrent()
        coEvery { service.getTags(songId) } throws IllegalStateException()

        val result = store.update(original, TimecodeTagType.NOTE, "Note", 4_000, null, TimecodeTagAction.NONE, false)

        assertEquals(updated, result)
        coVerify(exactly = 2) { service.getTags(songId) }
        assertEquals(listOf(original, other), store.cached(songId))
    }

    @Test
    fun failedReloadAfterDeleteKeepsThePreviouslyLoadedTags() = runTest {
        val removed = tag(1_000)
        val remaining = tag(2_000)
        coEvery { service.getTags(songId) } returns listOf(removed, remaining)
        coEvery { service.deleteTag(removed.id) } returns true
        val store = createStore()
        store.ensureLoaded(songId)
        runCurrent()
        coEvery { service.getTags(songId) } throws IllegalStateException()

        val deleted = store.delete(removed)

        assertTrue(deleted)
        coVerify(exactly = 2) { service.getTags(songId) }
        assertEquals(listOf(removed, remaining), store.cached(songId))
    }
}
