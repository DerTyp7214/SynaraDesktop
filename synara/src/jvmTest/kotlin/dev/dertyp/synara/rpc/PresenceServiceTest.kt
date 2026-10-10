package dev.dertyp.synara.rpc

import dev.dertyp.data.ChangeTopic
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.OnlineDevice
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IClientRequestService
import dev.dertyp.synara.Config
import dev.dertyp.synara.sync.DeviceIdentity
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.TestDispatchers
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.coEvery
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceServiceTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatchers(StandardTestDispatcher(scheduler))
    private val collectors = CoroutineScope(dispatchers.default + Job())

    private val reachable = MutableStateFlow(true)
    private val connection = MutableStateFlow(RpcServiceManager.ConnectionState.Authenticated)
    private val stream = MutableSharedFlow<ClientRequest>(extraBufferCapacity = 16)
    private val pendingStreams = ArrayDeque<Flow<ClientRequest>>()
    private val descriptions = mutableListOf<ClientDescription>()
    private val deviceTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private var devices: List<OnlineDevice> = emptyList()
    private var failDeviceLoads = false

    private val clientRequestService = mockk<IClientRequestService>()
    private val deviceIdentity = mockk<DeviceIdentity>()
    private val rpcServiceManager = mockk<RpcServiceManager>()
    private val changeHub = mockk<ChangeHub>()

    @BeforeTest
    fun setUp() {
        startTestKoin()
        Config.setIsRemoteControlEnabled(true)

        every { rpcServiceManager.isServerReachable } returns reachable
        every { rpcServiceManager.connectionState } returns connection

        every { deviceIdentity.deviceId } returns "device-1"
        every { deviceIdentity.platform } returns "test-platform"
        every { deviceIdentity.deviceName() } returns "Test device"

        every { clientRequestService.connect(capture(descriptions)) } answers {
            pendingStreams.removeFirstOrNull() ?: stream
        }
        coEvery { clientRequestService.getOnlineDevices() } answers {
            if (failDeviceLoads) throw IllegalStateException("devices failed")
            devices
        }
        coEvery { changeHub.refreshOn(ChangeTopic.ONLINE_DEVICES, any()) } coAnswers {
            val block = secondArg<suspend () -> Unit>()
            block()
            deviceTicks.collect { block() }
        }
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun createService() = PresenceService(
        clientRequestService,
        deviceIdentity,
        rpcServiceManager,
        changeHub,
        mockk<Logger>(relaxed = true),
        dispatchers
    )

    private fun startService(): PresenceService {
        val service = createService()
        service.start()
        scheduler.runCurrent()
        return service
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun device(index: Int) = OnlineDevice(
        sessionId = randomPlatformUUID(),
        deviceName = "Device $index",
        capabilities = setOf(ClientCapability.QUEUE_SYNC),
        isCurrent = false,
        connectedAt = 0L
    )

    private fun verifyConnects(count: Int) {
        verify(exactly = count) { clientRequestService.connect(any()) }
    }

    @Test
    fun connectIsNeverCalledWhileTheServerIsUnreachable() {
        reachable.value = false

        val service = startService()
        advance(60_000)

        verifyConnects(0)
        assertFalse(service.isConnected.value)
    }

    @Test
    fun connectIsNeverCalledWhileNotAuthenticated() {
        connection.value = RpcServiceManager.ConnectionState.LoginRequired

        val service = startService()
        advance(60_000)

        verifyConnects(0)
        assertFalse(service.isConnected.value)
    }

    @Test
    fun anActiveGateConnectsOnceWithTheDeviceIdentity() {
        val service = startService()

        verifyConnects(1)
        val description = descriptions.single()
        assertEquals("device-1", description.deviceId)
        assertEquals("Test device", description.deviceName)
        assertEquals("test-platform", description.platform)
        assertTrue(service.isConnected.value)
    }

    @Test
    fun queueSyncAloneOffersOnlyTheQueueSyncCapability() {
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(true)

        startService()

        assertEquals(setOf(ClientCapability.QUEUE_SYNC), descriptions.single().capabilities)
    }

    @Test
    fun remoteControlOffersAllThreeCapabilities() {
        Config.setIsRemoteControlEnabled(true)

        startService()

        assertEquals(
            setOf(ClientCapability.QUEUE_SYNC, ClientCapability.REMOTE_CONTROL, ClientCapability.REMOTE_VOLUME),
            descriptions.single().capabilities
        )
    }

    @Test
    fun noCapabilityIsOfferedWhenQueueSyncAndRemoteControlAreOff() {
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(false)

        startService()

        assertEquals(emptySet<ClientCapability>(), descriptions.single().capabilities)
    }

    @Test
    fun aRequestFromTheStreamAppearsOnRequests() {
        val service = startService()
        val received = mutableListOf<ClientRequest>()
        collectors.launch { service.requests.collect { received += it } }
        scheduler.runCurrent()
        val request = ClientRequest.UploadQueue(
            id = randomPlatformUUID(),
            requestedAt = 0L,
            requestedBySessionId = randomPlatformUUID()
        )

        assertTrue(stream.tryEmit(request))
        scheduler.runCurrent()

        assertEquals(listOf<ClientRequest>(request), received)
    }

    @Test
    fun aFailingStreamDisconnectsAndReconnectsAfterFiveSeconds() {
        val failing = Channel<ClientRequest>()
        pendingStreams += failing.receiveAsFlow()
        val service = startService()
        assertTrue(service.isConnected.value)

        failing.close(IllegalStateException("stream failed"))
        scheduler.runCurrent()
        assertFalse(service.isConnected.value)
        verifyConnects(1)

        advance(4_999)
        verifyConnects(1)
        assertFalse(service.isConnected.value)

        advance(1)
        verifyConnects(2)
        assertTrue(service.isConnected.value)
    }

    @Test
    fun anInactiveGateDisconnectsAndEmptiesTheOnlineDevices() {
        devices = listOf(device(1), device(2))
        val service = startService()
        assertTrue(service.isConnected.value)
        assertEquals(devices, service.onlineDevices.value)

        reachable.value = false
        scheduler.runCurrent()

        assertFalse(service.isConnected.value)
        assertEquals(emptyList<OnlineDevice>(), service.onlineDevices.value)
    }

    @Test
    fun connectingLoadsTheOnlineDevices() {
        devices = listOf(device(1), device(2))

        val service = startService()

        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun aReconnectReloadsTheOnlineDevicesWithoutAChangeTick() {
        val initial = listOf(device(1))
        devices = initial
        val failing = Channel<ClientRequest>()
        pendingStreams += failing.receiveAsFlow()
        val service = startService()
        assertEquals(initial, service.onlineDevices.value)

        failing.close(IllegalStateException("stream failed"))
        scheduler.runCurrent()
        devices = listOf(device(1), device(2))

        advance(4_999)
        verifyConnects(1)
        assertEquals(initial, service.onlineDevices.value)

        advance(1)
        verifyConnects(2)
        assertTrue(service.isConnected.value)
        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun theFirstConnectLoadsTheOnlineDevicesEvenIfTheChangeHubNeverTicks() {
        coEvery { changeHub.refreshOn(ChangeTopic.ONLINE_DEVICES, any()) } coAnswers {
            val block = secondArg<suspend () -> Unit>()
            deviceTicks.collect { block() }
        }
        devices = listOf(device(1), device(2))

        val service = startService()

        verifyConnects(1)
        assertTrue(service.isConnected.value)
        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun refreshOnlineDevicesReloadsTheList() {
        devices = listOf(device(1))
        val service = startService()

        devices = listOf(device(1), device(2))
        service.refreshOnlineDevices()
        scheduler.runCurrent()

        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun anOnlineDevicesChangeReloadsTheList() {
        devices = listOf(device(1))
        val service = startService()

        devices = listOf(device(2))
        assertTrue(deviceTicks.tryEmit(Unit))
        scheduler.runCurrent()

        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun aFailingDeviceLoadKeepsThePreviousList() {
        val previous = listOf(device(1))
        devices = previous
        val service = startService()

        failDeviceLoads = true
        devices = listOf(device(2))
        service.refreshOnlineDevices()
        scheduler.runCurrent()
        assertEquals(previous, service.onlineDevices.value)

        failDeviceLoads = false
        service.refreshOnlineDevices()
        scheduler.runCurrent()
        assertEquals(devices, service.onlineDevices.value)
    }

    @Test
    fun aFlagChangeReconnectsWithTheNewDescription() {
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(true)
        val service = startService()
        assertEquals(setOf(ClientCapability.QUEUE_SYNC), descriptions.single().capabilities)

        Config.setIsRemoteControlEnabled(true)
        scheduler.runCurrent()

        verifyConnects(2)
        assertEquals(
            setOf(ClientCapability.QUEUE_SYNC, ClientCapability.REMOTE_CONTROL, ClientCapability.REMOTE_VOLUME),
            descriptions.last().capabilities
        )
        assertTrue(service.isConnected.value)
    }

    @Test
    fun startingTwiceConnectsOnce() {
        val service = createService()

        service.start()
        service.start()
        scheduler.runCurrent()

        verifyConnects(1)
    }

    @Test
    fun markControlledPublishesTheControllerAndASecondCallReplacesIt() {
        val service = createService()
        scheduler.runCurrent()
        assertNull(service.controlledBy.value)
        assertFalse(service.isControlled.value)

        val first = randomPlatformUUID()
        service.markControlled(first, "Phone")
        scheduler.runCurrent()
        assertEquals(RemoteController(first, "Phone"), service.controlledBy.value)
        assertTrue(service.isControlled.value)

        val second = randomPlatformUUID()
        service.markControlled(second, null)
        scheduler.runCurrent()
        assertEquals(RemoteController(second, null), service.controlledBy.value)
        assertTrue(service.isControlled.value)
    }
}
