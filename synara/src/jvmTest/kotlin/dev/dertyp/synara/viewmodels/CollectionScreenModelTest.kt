package dev.dertyp.synara.viewmodels

import dev.dertyp.data.CollectionSearchResults
import dev.dertyp.data.MediaCollection
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.ICollectionService
import dev.dertyp.services.IImageService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.TestDispatchers
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionScreenModelTest {

    private val collectionId = randomPlatformUUID()
    private val collection = mockk<MediaCollection>()
    private val initial = mockk<CollectionSearchResults>()
    private val collectionService = mockk<ICollectionService> {
        coEvery { byId(collectionId) } returns collection
        coEvery { rankedSearch(collectionId, "", true, any(), any()) } returns initial
    }
    private val imageService = mockk<IImageService>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun TestScope.createModel(): CollectionScreenModel {
        val model = CollectionScreenModel(
            collectionId,
            collectionService,
            imageService,
            rpcServiceManager,
            TestDispatchers(StandardTestDispatcher(testScheduler))
        )
        runCurrent()
        return model
    }

    private fun TestScope.passDebounce() {
        advanceTimeBy(301)
        runCurrent()
    }

    @Test
    fun creatingTheModelLoadsTheCollectionAndItsContent() = runTest {
        val model = createModel()

        assertEquals(collection, model.state.value.collection)
        assertEquals(initial, model.state.value.results)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun supersedingAContentSearchInFlightLeavesNoError() = runTest {
        val newer = mockk<CollectionSearchResults>()
        val gate = CompletableDeferred<CollectionSearchResults>()
        coEvery { collectionService.rankedSearch(collectionId, "a", true, any(), any()) } coAnswers { gate.await() }
        coEvery { collectionService.rankedSearch(collectionId, "ab", true, any(), any()) } returns newer
        val model = createModel()
        model.setQuery("a")
        passDebounce()
        assertEquals(true, model.state.value.isLoading)

        model.setQuery("ab")
        runCurrent()

        assertEquals(true, model.state.value.isLoading)
        assertNull(model.state.value.error)
        assertEquals(initial, model.state.value.results)

        passDebounce()

        assertEquals(newer, model.state.value.results)
        assertEquals(false, model.state.value.isLoading)
        assertNull(model.state.value.error)
        coVerify(exactly = 1) { collectionService.rankedSearch(collectionId, "ab", true, any(), any()) }
    }

    @Test
    fun aRefreshAnsweredForAnOlderQueryDoesNotReplaceTheNewerSearch() = runTest {
        val newer = mockk<CollectionSearchResults>()
        val stale = mockk<CollectionSearchResults>()
        val model = createModel()
        val gate = CompletableDeferred<CollectionSearchResults>()
        coEvery { collectionService.rankedSearch(collectionId, "", true, any(), any()) } coAnswers { gate.await() }
        coEvery { collectionService.rankedSearch(collectionId, "ab", true, any(), any()) } returns newer
        model.refresh()
        runCurrent()
        assertEquals(true, model.isRefreshing.value)

        model.setQuery("ab")
        passDebounce()
        assertEquals(newer, model.state.value.results)
        assertEquals(false, model.state.value.isLoading)

        gate.complete(stale)
        runCurrent()

        assertEquals(newer, model.state.value.results)
        assertEquals(false, model.state.value.isLoading)
        assertEquals(false, model.isRefreshing.value)
    }

    @Test
    fun aRefreshThatFailsForAnOlderQueryLeavesTheNewerSearchUntouched() = runTest {
        val newer = CompletableDeferred<CollectionSearchResults>()
        val model = createModel()
        val gate = CompletableDeferred<CollectionSearchResults>()
        coEvery { collectionService.rankedSearch(collectionId, "", true, any(), any()) } coAnswers { gate.await() }
        coEvery { collectionService.rankedSearch(collectionId, "ab", true, any(), any()) } coAnswers { newer.await() }
        model.refresh()
        runCurrent()
        model.setQuery("ab")
        passDebounce()
        assertEquals(true, model.state.value.isLoading)

        gate.completeExceptionally(IOException("down"))
        runCurrent()

        assertEquals(true, model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun aFailingContentSearchStopsLoadingAndSetsTheError() = runTest {
        coEvery { collectionService.rankedSearch(collectionId, "a", true, any(), any()) } throws IOException("down")
        val model = createModel()

        model.setQuery("a")
        passDebounce()

        assertEquals(false, model.state.value.isLoading)
        assertNotNull(model.state.value.error)
        assertEquals(initial, model.state.value.results)
    }
}
