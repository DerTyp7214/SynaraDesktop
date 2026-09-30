package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.duration_seconds
import synara.synara.generated.resources.settings_fullscreen_hud_auto_hide_delay
import synara.synara.generated.resources.settings_fullscreen_hud_auto_hide_title
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FullscreenHudSettingsTest {

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun settingsTest(block: suspend ComposeUiTest.(autoHide: MutableState<Boolean>, delay: MutableIntState) -> Unit) =
        runComposeUiTest {
            val autoHide = mutableStateOf(false)
            val delay = mutableIntStateOf(3)
            setContent {
                MaterialTheme {
                    Column {
                        FullscreenHudSettings(
                            autoHide = autoHide.value,
                            delaySeconds = delay.intValue,
                            onAutoHideChange = { autoHide.value = it },
                            onDelayChange = { delay.intValue = it }
                        )
                    }
                }
            }
            block(autoHide, delay)
        }

    @Test
    fun switchTogglesAutoHide() = settingsTest { autoHide, _ ->
        onNodeWithText(getString(Res.string.settings_fullscreen_hud_auto_hide_title)).performClick()
        waitForIdle()
        assertEquals(true, autoHide.value)

        onNodeWithText(getString(Res.string.settings_fullscreen_hud_auto_hide_title)).performClick()
        waitForIdle()
        assertEquals(false, autoHide.value)
    }

    @Test
    fun sliderIsEnabledOnlyWithAutoHide() = settingsTest { autoHide, _ ->
        onNode(slider).assertIsNotEnabled()
        autoHide.value = true
        waitForIdle()
        onNode(slider).assertIsEnabled()
        onNode(slider).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3f, 1f..30f, 28))
        )
    }

    @Test
    fun sliderWritesWholeSecondsWithinRange() = settingsTest { autoHide, delay ->
        autoHide.value = true
        waitForIdle()
        onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        waitForIdle()
        assertEquals(10, delay.intValue)

        onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        waitForIdle()
        assertEquals(30, delay.intValue)
    }

    @Test
    fun valueTextShowsDelayInSeconds() = settingsTest { _, delay ->
        onNodeWithText(getString(Res.string.settings_fullscreen_hud_auto_hide_delay)).assertExists()
        onNodeWithText(getPluralString(Res.plurals.duration_seconds, 3, 3)).assertExists()

        delay.intValue = 1
        waitForIdle()
        onNodeWithText(getPluralString(Res.plurals.duration_seconds, 1, 1)).assertExists()

        delay.intValue = 30
        waitForIdle()
        onNodeWithText(getPluralString(Res.plurals.duration_seconds, 30, 30)).assertExists()
    }
}
