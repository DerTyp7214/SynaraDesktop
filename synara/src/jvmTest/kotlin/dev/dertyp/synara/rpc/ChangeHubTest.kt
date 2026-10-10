package dev.dertyp.synara.rpc

import dev.dertyp.data.Change
import dev.dertyp.data.ChangeTopic
import dev.dertyp.logging.Logger
import dev.dertyp.services.IChangeService
import dev.dertyp.synara.testing.TestDispatchers
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeHubTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val collectors = CoroutineScope(dispatchers.default + Job())

    private val reachable = MutableStateFlow(true)
    private val connection = MutableStateFlow(RpcServiceManager.ConnectionState.Authenticated)
    private val changes = MutableSharedFlow<Change>(extraBufferCapacity = 16)
    private val pendingStreams = ArrayDeque<Flow<Change>>()

    private val changeService = mockk<IChangeService>()
    private val rpcServiceManager = mockk<RpcServiceManager>()

    private lateinit var hub: ChangeHub

    @BeforeTest
    fun setUp() {
        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection
        every { changeService.observeChanges() } answers { pendingStreams.removeFirstOrNull() ?: changes }

        hub = ChangeHub(changeService, rpcServiceManager, mockk<Logger>(relaxed = true), dispatchers)
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun subscribe(topic: ChangeTopic): List<Unit> {
        val ticks = mutableListOf<Unit>()
        collectors.launch { hub.topic(topic).collect { ticks += it } }
        scheduler.runCurrent()
        return ticks
    }

    private fun startHub() {
        hub.start()
        scheduler.runCurrent()
    }

    private fun emitChange(topic: ChangeTopic) {
        assertTrue(changes.tryEmit(Change(topic)))
        scheduler.runCurrent()
    }

    private fun verifySubscriptions(count: Int) {
        verify(exactly = count) { changeService.observeChanges() }
    }

    @Test
    fun aTopicEmitsOnceImmediately() {
        val ticks = subscribe(ChangeTopic.LISTENS)

        advance(60_000)

        assertEquals(1, ticks.size)
    }

    @Test
    fun connectingTicksEveryKnownTopicOnce() {
        val known = ChangeTopic.entries.filter { it != ChangeTopic.UNKNOWN }
        val ticks = known.associateWith { subscribe(it) }
        val unknown = subscribe(ChangeTopic.UNKNOWN)

        startHub()

        known.forEach { topic -> assertEquals(2, ticks.getValue(topic).size, topic.name) }
        assertEquals(1, unknown.size)
    }

    @Test
    fun aChangeReachesEverySubscriberOfItsTopicAndNoOther() {
        val first = subscribe(ChangeTopic.LISTENS)
        val second = subscribe(ChangeTopic.LISTENS)
        val other = subscribe(ChangeTopic.HOME_CARDS)
        startHub()
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(2, other.size)

        emitChange(ChangeTopic.LISTENS)

        assertEquals(3, first.size)
        assertEquals(3, second.size)
        assertEquals(2, other.size)
    }

    @Test
    fun anUnknownTopicIsDropped() {
        val unknown = subscribe(ChangeTopic.UNKNOWN)
        val listens = subscribe(ChangeTopic.LISTENS)
        startHub()

        emitChange(ChangeTopic.UNKNOWN)

        assertEquals(1, unknown.size)
        assertEquals(2, listens.size)
    }

    @Test
    fun theStreamIsNotOpenedWhileTheServerIsUnreachable() {
        reachable.value = false

        startHub()
        advance(60_000)

        verifySubscriptions(0)
    }

    @Test
    fun theStreamIsNotOpenedWhileNotAuthenticated() {
        connection.value = RpcServiceManager.ConnectionState.LoginRequired

        startHub()
        advance(60_000)

        verifySubscriptions(0)
    }

    @Test
    fun goingInactiveCancelsTheStreamAndDoesNotReconnect() {
        startHub()
        verifySubscriptions(1)
        assertEquals(1, changes.subscriptionCount.value)

        reachable.value = false
        scheduler.runCurrent()
        assertEquals(0, changes.subscriptionCount.value)

        advance(60_000)
        verifySubscriptions(1)

        reachable.value = true
        scheduler.runCurrent()
        verifySubscriptions(2)
        assertEquals(1, changes.subscriptionCount.value)
    }

    @Test
    fun aFailingStreamReconnectsAfterFiveSecondsAndTicksAgain() {
        val failing = Channel<Change>()
        pendingStreams += failing.receiveAsFlow()
        val ticks = subscribe(ChangeTopic.LISTENS)
        startHub()
        assertEquals(2, ticks.size)

        failing.close(IllegalStateException("stream failed"))
        scheduler.runCurrent()

        advance(4_999)
        verifySubscriptions(1)
        assertEquals(2, ticks.size)

        advance(1)
        verifySubscriptions(2)
        assertEquals(3, ticks.size)

        emitChange(ChangeTopic.LISTENS)
        assertEquals(4, ticks.size)
    }

    @Test
    fun aStreamThatEndsNormallyAlsoReconnectsAfterFiveSeconds() {
        val ending = Channel<Change>()
        pendingStreams += ending.receiveAsFlow()
        val ticks = subscribe(ChangeTopic.LISTENS)
        startHub()
        assertEquals(2, ticks.size)

        ending.close()
        scheduler.runCurrent()

        advance(4_999)
        verifySubscriptions(1)
        assertEquals(2, ticks.size)

        advance(1)
        verifySubscriptions(2)
        assertEquals(3, ticks.size)

        emitChange(ChangeTopic.LISTENS)
        assertEquals(4, ticks.size)
    }

    @Test
    fun aThrowingRefreshBlockDoesNotStopLaterRefreshes() {
        var calls = 0
        collectors.launch {
            hub.refreshOn(ChangeTopic.LISTENS) {
                calls++
                if (calls == 1) throw IllegalStateException("refresh failed")
            }
        }
        scheduler.runCurrent()
        assertEquals(1, calls)

        startHub()
        assertEquals(2, calls)

        emitChange(ChangeTopic.LISTENS)
        assertEquals(3, calls)
    }

    @Test
    fun startingTwiceOpensOneStream() {
        hub.start()
        hub.start()
        scheduler.runCurrent()
        advance(60_000)

        verifySubscriptions(1)
    }
}
