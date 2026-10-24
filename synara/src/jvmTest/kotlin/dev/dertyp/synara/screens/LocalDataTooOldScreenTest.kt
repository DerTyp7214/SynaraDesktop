package dev.dertyp.synara.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.dertyp.synara.db.MigrationBase
import dev.dertyp.synara.ui.TextClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LocalDataTooOldScreenTest {
    private var clipboardText: String? = null
    private val openedUris = mutableListOf<String>()

    private fun screenTest(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalClipboard provides TextClipboard(read = { clipboardText }, write = { clipboardText = it }),
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUris += uri
                    }
                }
            ) {
                MaterialTheme {
                    LocalDataTooOldScreen(foundVersion = "1.13")
                }
            }
        }
        block()
    }

    @Test
    fun theScreenShowsThePacmanServerOfTheSteppingStone() = screenTest {
        onNodeWithTag(LOCAL_DATA_TOO_OLD_SCREEN_TAG).assertExists()
        onNodeWithTag(LOCAL_DATA_TOO_OLD_ARCH_COMMANDS_TAG).assertTextEquals(steppingStoneArchCommands)
        assertTrue(steppingStoneArchCommands.contains("Server = ${MigrationBase.STEPPING_STONE_PACMAN_SERVER}"))
        assertTrue(MigrationBase.STEPPING_STONE_PACMAN_SERVER.endsWith("/download/v${MigrationBase.STEPPING_STONE}"))
    }

    @Test
    fun theCopyButtonCopiesTheArchCommands() = screenTest {
        assertNull(clipboardText)

        onNodeWithTag(LOCAL_DATA_TOO_OLD_COPY_TAG).performScrollTo().performClick()
        waitForIdle()

        assertEquals(steppingStoneArchCommands, clipboardText)
    }

    @Test
    fun theReleaseButtonOpensTheReleasePageOfTheSteppingStone() = screenTest {
        onNodeWithTag(LOCAL_DATA_TOO_OLD_RELEASE_TAG).performScrollTo().performClick()
        waitForIdle()

        assertEquals(listOf(MigrationBase.STEPPING_STONE_RELEASE_URL), openedUris)
        assertTrue(MigrationBase.STEPPING_STONE_RELEASE_URL.endsWith("/tag/v${MigrationBase.STEPPING_STONE}"))
    }
}
