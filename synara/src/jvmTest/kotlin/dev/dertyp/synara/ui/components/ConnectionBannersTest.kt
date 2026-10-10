package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConnectionBannersTest {

    private val connectionState = MutableStateFlow(ConnectionState.LoginRequired)
    private val serverReachable = MutableStateFlow(true)
    private val sslOverride = MutableStateFlow<Boolean?>(null)
    private val rpcServiceManager = mockk<RpcServiceManager>(relaxed = true)

    private val actions = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
    private val texts = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private fun bannerTest(block: suspend ComposeUiTest.() -> Unit) = runComposeUiTest {
        every { rpcServiceManager.connectionState } returns connectionState
        every { rpcServiceManager.isServerReachable } returns serverReachable
        every { rpcServiceManager.sessionSslOverride } returns sslOverride
        setContent {
            MaterialTheme {
                Box {
                    ConnectionBanners(rpcServiceManager)
                }
            }
        }
        block()
    }

    private fun ComposeUiTest.assertFallbackWarningShown() {
        waitForIdle()
        onAllNodes(actions).assertCountEquals(2)
    }

    private fun ComposeUiTest.assertNothingShown() {
        waitForIdle()
        onAllNodes(actions).assertCountEquals(0)
        onAllNodes(texts).assertCountEquals(0)
    }

    private fun ComposeUiTest.assertOnlyOfflineBannerShown() {
        waitForIdle()
        onAllNodes(actions).assertCountEquals(0)
        onAllNodes(texts).assertCountEquals(1)
    }

    @Test
    fun theFallbackWarningIsShownOnTheLoginScreen() = bannerTest {
        sslOverride.value = false

        assertFallbackWarningShown()
    }

    @Test
    fun theFallbackWarningAppearsOnTheLoginScreenWhenTheFallbackStartsAfterTheSetup() = bannerTest {
        connectionState.value = ConnectionState.SetupRequired
        assertNothingShown()

        connectionState.value = ConnectionState.LoginRequired
        assertNothingShown()

        sslOverride.value = false
        assertFallbackWarningShown()
    }

    @Test
    fun noWarningIsShownOnTheLoginScreenWithoutTheFallback() = bannerTest {
        assertNothingShown()

        sslOverride.value = true
        assertNothingShown()
    }

    @Test
    fun theFallbackWarningStaysVisibleFromTheLoginIntoTheSession() = bannerTest {
        sslOverride.value = false
        assertFallbackWarningShown()

        connectionState.value = ConnectionState.Authenticated
        assertFallbackWarningShown()
    }

    @Test
    fun theFallbackWarningIsHiddenWhileLoadingAndDuringSetup() = bannerTest {
        sslOverride.value = false
        connectionState.value = ConnectionState.Loading
        assertNothingShown()

        connectionState.value = ConnectionState.SetupRequired
        assertNothingShown()
    }

    @Test
    fun retryOnTheLoginScreenResetsTheFallbackAndReconnects() = bannerTest {
        sslOverride.value = false
        assertFallbackWarningShown()

        onAllNodes(actions)[0].performClick()
        waitForIdle()

        verify(exactly = 1) { rpcServiceManager.resetSslSession() }
        verify(exactly = 1) { rpcServiceManager.retryConnection() }
    }

    @Test
    fun aWarningDismissedOnTheLoginScreenIsShownAgainAfterTheLogin() = bannerTest {
        sslOverride.value = false
        assertFallbackWarningShown()

        onAllNodes(actions)[1].performClick()
        assertNothingShown()
        verify(exactly = 0) { rpcServiceManager.resetSslSession() }

        connectionState.value = ConnectionState.Authenticated
        assertFallbackWarningShown()
    }

    @Test
    fun aWarningDismissedInTheSessionIsShownAgainOnTheLoginScreen() = bannerTest {
        connectionState.value = ConnectionState.Authenticated
        sslOverride.value = false
        assertFallbackWarningShown()

        onAllNodes(actions)[1].performClick()
        assertNothingShown()

        connectionState.value = ConnectionState.LoginRequired
        assertFallbackWarningShown()
    }

    @Test
    fun theOfflineBannerIsShownOnlyWhileAuthenticated() = bannerTest {
        serverReachable.value = false
        assertNothingShown()

        connectionState.value = ConnectionState.Authenticated
        assertOnlyOfflineBannerShown()

        connectionState.value = ConnectionState.SetupRequired
        assertNothingShown()
    }
}
