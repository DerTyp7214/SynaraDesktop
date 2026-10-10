package dev.dertyp.synara.podcast

import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.logging.Logger
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastProgressStoreImplTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val collectorScope = CoroutineScope(dispatcher + Job())
    private val reachable = MutableStateFlow(false)
    private val connection = MutableStateFlow(ConnectionState.Loading)
    private val remote = MutableSharedFlow<PodcastEpisodeProgress>(extraBufferCapacity = 8)
    private val podcastService = mockk<IPodcastService>()
    private val emitted = mutableListOf<PodcastEpisodeProgress>()
    private var subscriptions = 0

    private lateinit var rpcServiceManager: RpcServiceManager

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setPodcastsEnabled(false)
        Config.setPodcastPlaybackSpeed(1f)

        rpcServiceManager = mockk()
        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection
        every { podcastService.observeProgress() } answers {
            subscriptions++
            remote
        }
    }

    @AfterTest
    fun tearDown() {
        collectorScope.cancel()
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun createStore(): PodcastProgressStoreImpl {
        val store = PodcastProgressStoreImpl(
            podcastService = podcastService,
            rpcServiceManager = rpcServiceManager,
            logger = mockk<Logger>(relaxed = true),
            dispatchers = TestDispatchers(dispatcher)
        )
        collectorScope.launch { store.updates.collect { emitted += it } }
        scheduler.runCurrent()
        return store
    }

    private fun activate() {
        reachable.value = true
        connection.value = ConnectionState.Authenticated
        Config.setPodcastsEnabled(true)
        scheduler.runCurrent()
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun assertObservationStopsAndResumes(store: PodcastProgressStoreImpl, deactivate: () -> Unit) {
        val episode = testEpisode(1)
        activate()
        assertEquals(1, subscriptions)
        assertEquals(1, remote.subscriptionCount.value)

        deactivate()
        scheduler.runCurrent()
        assertEquals(0, remote.subscriptionCount.value)

        advance(60_000)
        assertEquals(1, subscriptions)
        assertEquals(0, remote.subscriptionCount.value)

        activate()
        assertEquals(2, subscriptions)
        assertEquals(1, remote.subscriptionCount.value)

        val progress = testProgress(episode, positionMs = 1_000, updatedAt = 10)
        remote.tryEmit(progress)
        scheduler.runCurrent()
        assertEquals(progress, store.latest.value[episode.id])
    }

    @Test
    fun recordStoresAndEmitsOnlyAStrictlyNewerProgress() {
        val episode = testEpisode(1)
        val other = testEpisode(2)
        val store = createStore()
        val initial = testProgress(episode, positionMs = 1_000, updatedAt = 100)
        val older = testProgress(episode, positionMs = 2_000, updatedAt = 99)
        val sameAge = testProgress(episode, positionMs = 3_000, updatedAt = 100)
        val newer = testProgress(episode, positionMs = 4_000, updatedAt = 101)
        val unrelated = testProgress(other, positionMs = 5_000, updatedAt = 1)

        store.record(initial)
        scheduler.runCurrent()
        assertEquals(initial, store.latest.value[episode.id])
        assertEquals(listOf(initial), emitted)

        store.record(older)
        store.record(sameAge)
        scheduler.runCurrent()
        assertEquals(initial, store.latest.value[episode.id])
        assertEquals(listOf(initial), emitted)

        store.record(newer)
        store.record(unrelated)
        scheduler.runCurrent()
        assertEquals(mapOf(episode.id to newer, other.id to unrelated), store.latest.value)
        assertEquals(listOf(initial, newer, unrelated), emitted)
        assertEquals(0, subscriptions)
    }

    @Test
    fun observationStartsOnlyWhenReachableEnabledAndAuthenticated() {
        val episode = testEpisode(1)
        val store = createStore()
        assertEquals(0, subscriptions)

        reachable.value = true
        scheduler.runCurrent()
        assertEquals(0, subscriptions)

        connection.value = ConnectionState.Authenticated
        scheduler.runCurrent()
        assertEquals(0, subscriptions)

        reachable.value = false
        scheduler.runCurrent()
        Config.setPodcastsEnabled(true)
        scheduler.runCurrent()
        assertEquals(0, subscriptions)

        connection.value = ConnectionState.LoginRequired
        scheduler.runCurrent()
        reachable.value = true
        scheduler.runCurrent()
        assertEquals(0, subscriptions)

        connection.value = ConnectionState.Authenticated
        scheduler.runCurrent()
        assertEquals(1, subscriptions)
        assertEquals(1, remote.subscriptionCount.value)

        val progress = testProgress(episode, positionMs = 1_000, updatedAt = 10)
        remote.tryEmit(progress)
        scheduler.runCurrent()
        assertEquals(progress, store.latest.value[episode.id])
        assertEquals(listOf(progress), emitted)
    }

    @Test
    fun observationIsCancelledWhenTheServerBecomesUnreachable() {
        assertObservationStopsAndResumes(createStore()) { reachable.value = false }
    }

    @Test
    fun observationIsCancelledWhenTheConnectionIsNoLongerAuthenticated() {
        assertObservationStopsAndResumes(createStore()) { connection.value = ConnectionState.LoginRequired }
    }

    @Test
    fun observationIsCancelledWhenPodcastsAreDisabled() {
        assertObservationStopsAndResumes(createStore()) { Config.setPodcastsEnabled(false) }
    }

    @Test
    fun aFailingProgressStreamIsSubscribedAgainAfterFiveSeconds() {
        val episode = testEpisode(1)
        val failing: Flow<PodcastEpisodeProgress> = flow { throw IllegalStateException("stream failed") }
        every { podcastService.observeProgress() } answers {
            subscriptions++
            if (subscriptions == 1) failing else remote
        }
        val store = createStore()

        activate()
        assertEquals(1, subscriptions)
        assertEquals(0, remote.subscriptionCount.value)

        advance(4_999)
        assertEquals(1, subscriptions)

        advance(1)
        assertEquals(2, subscriptions)
        assertEquals(1, remote.subscriptionCount.value)

        val progress = testProgress(episode, positionMs = 1_000, updatedAt = 10)
        remote.tryEmit(progress)
        scheduler.runCurrent()
        assertEquals(progress, store.latest.value[episode.id])
        assertTrue(emitted.contains(progress))
    }
}
