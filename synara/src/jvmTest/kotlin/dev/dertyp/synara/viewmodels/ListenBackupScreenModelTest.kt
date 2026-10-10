package dev.dertyp.synara.viewmodels

import dev.dertyp.data.ListenBackupConfig
import dev.dertyp.data.ListenBackupConnectionTest
import dev.dertyp.data.ListenBackupState
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IListenBackupService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.UnconfinedDispatchers
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenBackupScreenModelTest {

    private val serverId = randomPlatformUUID()
    private val listenBackupService = mockk<IListenBackupService>()
    private val rpcServiceManager = mockk<RpcServiceManager> {
        coEvery { awaitAuthentication() } just Runs
    }

    private fun backupState(config: ListenBackupConfig = ListenBackupConfig()) = ListenBackupState(
        config = config,
        hasKey = false,
        serverId = serverId,
        lastSyncAt = null,
        lastSyncedUpdatedAt = 0,
        lastSyncedCount = 0,
        lastError = null,
        pendingCount = 0
    )

    private fun createModel() = ListenBackupScreenModel(listenBackupService, rpcServiceManager, UnconfinedDispatchers)

    @Test
    fun theFirstStateSeedsTheFormAndLaterStatesDoNotOverwriteEdits() {
        val first = backupState(ListenBackupConfig(enabled = true, url = "http://a", batchSize = 250))
        val stream = MutableStateFlow(first)
        every { listenBackupService.observeState() } returns stream

        val model = createModel()

        assertEquals(first, model.state.value.backupState)
        assertTrue(model.state.value.enabled)
        assertEquals("http://a", model.state.value.url)
        assertEquals("250", model.state.value.batchSize)
        assertFalse(model.state.value.isLoading)

        model.setUrl("http://edited")
        model.setEnabled(false)
        model.setBatchSize("5")
        val second = backupState(ListenBackupConfig(enabled = true, url = "http://b", batchSize = 999))
        stream.value = second

        assertEquals(second, model.state.value.backupState)
        assertFalse(model.state.value.enabled)
        assertEquals("http://edited", model.state.value.url)
        assertEquals("5", model.state.value.batchSize)
    }

    @Test
    fun theBatchSizeRejectsNonDigitsAndAcceptsAnEmptyValue() {
        every { listenBackupService.observeState() } returns MutableStateFlow(backupState())
        val model = createModel()
        assertEquals("1000", model.state.value.batchSize)

        model.setBatchSize("12a")
        assertEquals("1000", model.state.value.batchSize)
        model.setBatchSize("-5")
        assertEquals("1000", model.state.value.batchSize)
        model.setBatchSize("")
        assertEquals("", model.state.value.batchSize)
        model.setBatchSize("42")
        assertEquals("42", model.state.value.batchSize)
    }

    @Test
    fun savingTrimsTheUrlSendsABlankKeyAsNullAndAnEmptyBatchAsThousand() = runTest {
        every { listenBackupService.observeState() } returns MutableStateFlow(backupState())
        val sent = slot<ListenBackupConfig>()
        coEvery { listenBackupService.updateConfig(capture(sent)) } returns backupState()
        val model = createModel()
        model.setEnabled(true)
        model.setUrl("  http://x  ")
        model.setKey("   ")
        model.setBatchSize("")

        val saved = model.save()

        assertTrue(saved)
        assertEquals(ListenBackupConfig(enabled = true, url = "http://x", key = null, batchSize = 1000), sent.captured)
        assertFalse(model.state.value.isBusy)
    }

    @Test
    fun savingSendsTheKeyAndClearsItOnSuccess() = runTest {
        every { listenBackupService.observeState() } returns MutableStateFlow(backupState())
        val sent = slot<ListenBackupConfig>()
        coEvery { listenBackupService.updateConfig(capture(sent)) } returns backupState()
        val model = createModel()
        model.setKey("secret")
        model.setBatchSize("250")

        model.save()

        assertEquals("secret", sent.captured.key)
        assertEquals(250, sent.captured.batchSize)
        assertEquals("", model.state.value.key)
    }

    @Test
    fun aFailedSaveReturnsFalseKeepsTheKeyAndSetsTheError() = runTest {
        every { listenBackupService.observeState() } returns MutableStateFlow(backupState())
        coEvery { listenBackupService.updateConfig(any()) } throws IllegalArgumentException("bad")
        val model = createModel()
        model.setKey("secret")

        val saved = model.save()

        assertFalse(saved)
        assertEquals("secret", model.state.value.key)
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.isBusy)
    }

    @Test
    fun aFailingStateStreamFallsBackToASingleStateRequest() {
        val fetched = backupState(ListenBackupConfig(enabled = true, url = "http://a", batchSize = 7))
        every { listenBackupService.observeState() } returns flow { throw IOException("stream down") }
        coEvery { listenBackupService.getState() } returns fetched

        val model = createModel()

        assertEquals(fetched, model.state.value.backupState)
        assertEquals("http://a", model.state.value.url)
        assertEquals("7", model.state.value.batchSize)
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test
    fun whenBothTheStreamAndTheFallbackFailTheErrorIsSet() {
        every { listenBackupService.observeState() } returns flow { throw IOException("stream down") }
        coEvery { listenBackupService.getState() } throws IOException("down")

        val model = createModel()

        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.backupState)
    }

    @Test
    fun testingTheConnectionSendsTheTrimmedFormConfigAndStoresTheResult() = runTest {
        every { listenBackupService.observeState() } returns MutableStateFlow(backupState())
        val probe = ListenBackupConnectionTest(ok = true)
        coEvery { listenBackupService.testConnection(any()) } returns probe
        val model = createModel()
        model.setUrl(" http://x ")

        model.testConnection()

        coVerify(exactly = 1) { listenBackupService.testConnection(match { it?.url == "http://x" }) }
        assertEquals(probe, model.state.value.testResult)
        assertFalse(model.state.value.isBusy)
    }
}
