package dev.dertyp.synara.viewmodels

import dev.dertyp.data.ServerValidationResult
import dev.dertyp.synara.rpc.RpcServiceManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs

class SetupScreenModelTest {

    private val rpcServiceManager = mockk<RpcServiceManager>(relaxed = true)
    private val model = SetupScreenModel(rpcServiceManager)

    private fun validated(result: ServerValidationResult) {
        coEvery { rpcServiceManager.validateServer("music.example", 8443, "/api", true) } returns result
        runBlocking { model.testConnection("music.example", 8443, "/api", true) }
    }

    @Test
    fun aTlsFailureWithWorkingPlaintextStoresTheServerWithSslOn() {
        validated(ServerValidationResult(validated = true, useSsl = false, tlsFailed = true))

        model.setServer()

        assertIs<TestConnectionResult.Success>(model.testConnectionResult.value)
        verify(exactly = 1) { rpcServiceManager.setServer("music.example", 8443, true, "/api") }
    }

    @Test
    fun aServerWithoutTlsIsStoredWithSslOff() {
        validated(ServerValidationResult(validated = true, useSsl = false))

        model.setServer()

        verify(exactly = 1) { rpcServiceManager.setServer("music.example", 8443, false, "/api") }
    }

    @Test
    fun aServerWithWorkingTlsIsStoredWithSslOn() {
        validated(ServerValidationResult(validated = true, useSsl = true))

        model.setServer()

        verify(exactly = 1) { rpcServiceManager.setServer("music.example", 8443, true, "/api") }
    }

    @Test
    fun aServerThatWasNotValidatedIsNotStored() {
        validated(ServerValidationResult(validated = false, useSsl = false))

        model.setServer()

        assertIs<TestConnectionResult.Error>(model.testConnectionResult.value)
        verify(exactly = 0) { rpcServiceManager.setServer(any(), any(), any(), any()) }
    }
}
