package dev.dertyp.synara.viewmodels

import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.logging.Logger
import dev.dertyp.services.IAuthService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.testing.UnconfinedDispatchers
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.error_server_unreachable
import synara.synara.generated.resources.login_failed
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginScreenModelTest {

    private val authService = mockk<IAuthService>()
    private val rpcServiceManager = mockk<RpcServiceManager>(relaxed = true)
    private var encryptionCheck: suspend () -> Boolean = { true }

    private fun createModel(): LoginScreenModel {
        coEvery { rpcServiceManager.checkSslSupport() } coAnswers { encryptionCheck() }
        return LoginScreenModel(
            authService,
            rpcServiceManager,
            UnconfinedDispatchers,
            mockk<Logger>(relaxed = true)
        )
    }

    @Test
    fun theModelStartsIdle() {
        assertEquals(LoginResult.Idle, createModel().loginResult.value)
    }

    @Test
    fun aTransportFailureMapsToTheServerUnreachableMessage() {
        coEvery { authService.authenticate(any(), any()) } throws IOException("down")
        val model = createModel()

        model.login("admin", "secret")

        val error = assertIs<LoginResult.Error>(model.loginResult.value)
        assertEquals(Res.string.error_server_unreachable, error.message)
        coVerify(exactly = 0) { rpcServiceManager.updateAuth(any()) }
    }

    @Test
    fun anyOtherFailureMapsToTheLoginFailedMessage() {
        coEvery { authService.authenticate(any(), any()) } throws IllegalArgumentException("wrong password")
        val model = createModel()

        model.login("admin", "wrong")

        val error = assertIs<LoginResult.Error>(model.loginResult.value)
        assertEquals(Res.string.login_failed, error.message)
        coVerify(exactly = 0) { rpcServiceManager.updateAuth(any()) }
    }

    @Test
    fun aSuccessfulLoginUpdatesTheAuthAndYieldsSuccess() {
        val response = mockk<AuthenticationResponse>()
        coEvery { authService.authenticate("admin", "secret") } returns response
        val model = createModel()

        model.login("admin", "secret")

        assertEquals(LoginResult.Success, model.loginResult.value)
        coVerify(exactly = 1) { rpcServiceManager.updateAuth(response) }
    }

    @Test
    fun theLoginIsNotSentWhenItsOwnCheckFallsBackToAnUnencryptedConnection() {
        coEvery { authService.authenticate(any(), any()) } returns mockk<AuthenticationResponse>()
        val checks = ArrayDeque(listOf(false, true))
        encryptionCheck = { checks.removeFirst() }
        val model = createModel()

        model.login("admin", "secret")

        assertEquals(LoginResult.Idle, model.loginResult.value)
        coVerify(exactly = 0) { authService.authenticate(any(), any()) }
        coVerify(exactly = 0) { rpcServiceManager.updateAuth(any()) }

        model.login("admin", "secret")

        assertEquals(LoginResult.Success, model.loginResult.value)
        coVerify(exactly = 1) { authService.authenticate("admin", "secret") }
    }

    @Test
    fun theEncryptionIsCheckedBeforeTheCredentialsAreSent() {
        coEvery { authService.authenticate(any(), any()) } returns mockk<AuthenticationResponse>()
        val model = createModel()

        model.login("admin", "secret")

        coVerifyOrder {
            rpcServiceManager.checkSslSupport()
            authService.authenticate("admin", "secret")
        }
    }

    @Test
    fun openingTheLoginScreenRunsTheEncryptionCheckAndBlocksTheLoginUntilItEnded() {
        coEvery { authService.authenticate(any(), any()) } returns mockk<AuthenticationResponse>()
        val pending = CompletableDeferred<Boolean>()
        encryptionCheck = { pending.await() }
        val model = createModel()
        assertFalse(model.encryptionCheckRunning.value)

        model.checkEncryption()

        assertTrue(model.encryptionCheckRunning.value)
        coVerify(exactly = 1) { rpcServiceManager.checkSslSupport() }

        model.login("admin", "secret")

        assertEquals(LoginResult.Idle, model.loginResult.value)
        coVerify(exactly = 0) { authService.authenticate(any(), any()) }

        encryptionCheck = { true }
        pending.complete(false)

        assertFalse(model.encryptionCheckRunning.value)
        coVerify(exactly = 0) { authService.authenticate(any(), any()) }

        model.login("admin", "secret")

        assertEquals(LoginResult.Success, model.loginResult.value)
        coVerify(exactly = 1) { authService.authenticate("admin", "secret") }
    }

    @Test
    fun resetReturnsToIdleAfterAnError() {
        coEvery { authService.authenticate(any(), any()) } throws IllegalArgumentException("wrong password")
        val model = createModel()
        model.login("admin", "wrong")
        assertIs<LoginResult.Error>(model.loginResult.value)

        model.reset()

        assertEquals(LoginResult.Idle, model.loginResult.value)
    }

    @Test
    fun goingBackToTheSetupResetsTheServerConnection() {
        val model = createModel()

        model.backToSetup()

        verify(exactly = 1) { rpcServiceManager.resetToSetup() }
    }
}
