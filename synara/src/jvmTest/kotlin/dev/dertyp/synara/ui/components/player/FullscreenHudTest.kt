package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.FullscreenIdleBox
import dev.dertyp.synara.ui.LocalPlayerHudIdle
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.WindowActions
import org.jetbrains.compose.resources.getString
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.fullscreen_hud_auto_hide_disable
import synara.synara.generated.resources.fullscreen_hud_auto_hide_enable
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FullscreenHudTest {

    private class FakeWindowActions : WindowActions {
        var fullscreenState by mutableStateOf(false)
        val cursor = mutableListOf<Boolean>()
        override fun toggleFullscreen() {
            fullscreenState = !fullscreenState
        }

        override fun setFullscreen(enabled: Boolean) {
            fullscreenState = enabled
        }

        override val isFullscreen: Boolean get() = fullscreenState
        override fun setCursorVisible(enabled: Boolean) {
            cursor += enabled
        }
    }

    private class Probe {
        val window = FakeWindowActions()
        val autoHide = mutableStateOf(true)
        val overlayOpen = mutableStateOf(false)
        val baseCenter = mutableStateOf(Offset(200f, 150f))
        val shift = mutableStateOf(HudShift(cover = Offset(SHIFT_X, SHIFT_Y), line = Offset(SHIFT_X, LINE_SHIFT_Y)))
        lateinit var hud: PlayerHud
        lateinit var particleCenter: State<Offset>
        var compositions = 0
        var measures = 0
        var chromeClicks = 0
        var toggles = 0
        var anchor: LayoutCoordinates? = null
        var drawn: LayoutCoordinates? = null
        var lineCompositions = 0
        var lineMeasures = 0
        var line: LayoutCoordinates? = null
        var lineColumn: LayoutCoordinates? = null

        fun lineWidth(): Int = line!!.size.width
        fun lineCenter(): Offset = line!!.positionInRoot() + Offset(line!!.size.width / 2f, line!!.size.height / 2f)
        fun lineColumnCenter(): Offset = lineColumn!!.positionInRoot() + Offset(lineColumn!!.size.width / 2f, line!!.size.height / 2f)

        fun drawnOffset(): Offset = drawn!!.positionInRoot() - anchor!!.positionInRoot()
    }

    @Composable
    private fun StandIn(hud: PlayerHud, shift: State<HudShift>, probe: Probe) {
        SideEffect { probe.compositions++ }
        Box(
            modifier = Modifier
                .size(100.dp)
                .layout { measurable, constraints ->
                    probe.measures++
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .onPlaced { probe.anchor = it }
                .hudShift(hud.progress) { shift.value.cover }
                .onPlaced { probe.drawn = it }
        )
    }

    @Composable
    private fun LineStandIn(hud: PlayerHud, shift: State<HudShift>, probe: Probe) {
        SideEffect { probe.lineCompositions++ }
        Box(
            modifier = Modifier
                .hudLineWidth(hud.progress, LINE_SCALE_WITH_PANEL) { hud.contentWidth.intValue * LINE_SCALE_ALONE }
                .height(10.dp)
                .layout { measurable, constraints ->
                    probe.lineMeasures++
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .hudShift(hud.progress) { shift.value.line }
                .onPlaced { probe.line = it }
        )
    }

    private fun hudTest(block: suspend ComposeUiTest.(Probe) -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        val probe = Probe()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWindowActions provides probe.window) {
                    FullscreenIdleBox(
                        windowActions = probe.window,
                        overlayOpen = probe.overlayOpen.value,
                        hudAutoHide = probe.autoHide.value,
                        hudDelaySeconds = DELAY_SECONDS,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val hidden = playerHudHidden(
                            autoHide = probe.autoHide.value,
                            isFullscreen = probe.window.isFullscreen,
                            isExpanded = true,
                            idle = LocalPlayerHudIdle.current,
                            overlayOpen = probe.overlayOpen.value
                        )
                        val hud = rememberPlayerHud(hidden)
                        probe.hud = hud
                        probe.particleCenter = rememberHudShiftedCenter(probe.baseCenter, hud.progress) {
                            probe.shift.value.cover
                        }
                        Column {
                            FullscreenHudToggle(
                                autoHide = probe.autoHide.value,
                                onToggle = { probe.toggles++ }
                            )
                            Box(modifier = Modifier.size(80.dp).hudChrome(hud)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .semantics { contentDescription = CHROME }
                                        .clickable { probe.chromeClicks++ }
                                )
                            }
                            StandIn(hud, probe.shift, probe)
                            Row(
                                modifier = Modifier
                                    .width(CONTENT_WIDTH.dp)
                                    .onSizeChanged { hud.contentWidth.intValue = it.width }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(COLUMN_WIDTH.dp)
                                        .onPlaced { probe.lineColumn = it },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    LineStandIn(hud, probe.shift, probe)
                                }
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        onRoot().performMouseInput { moveTo(Offset(1000f, 700f)) }
        mainClock.advanceTimeByFrame()
        block(probe)
    }

    private fun ComposeUiTest.advanceBy(millis: Long) {
        mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.enterFullscreen(probe: Probe) {
        probe.window.fullscreenState = true
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.hideCompletely(probe: Probe) {
        advanceBy(DELAY_SECONDS * 1000L + 2 * FRAME_MS)
        assertTrue(probe.hud.hidden.value)
        advanceBy(HUD_HIDE_MILLIS + 2 * FRAME_MS)
        assertEquals(1f, probe.hud.progress.value)
    }

    private fun ComposeUiTest.moveMouse(to: Offset) {
        onRoot().performMouseInput { moveTo(to) }
        mainClock.advanceTimeByFrame()
    }

    private fun assertClose(expected: Offset, actual: Offset, message: String) {
        assertTrue(abs(expected.x - actual.x) < 0.5f && abs(expected.y - actual.y) < 0.5f, "$message: expected $expected, was $actual")
    }

    @Test
    fun toggleIsOnlyShownInFullscreen() = hudTest { probe ->
        val disable = getString(Res.string.fullscreen_hud_auto_hide_disable)
        onNodeWithContentDescription(disable).assertDoesNotExist()

        enterFullscreen(probe)
        onNodeWithContentDescription(disable).assertExists().performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(1, probe.toggles)

        probe.autoHide.value = false
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription(getString(Res.string.fullscreen_hud_auto_hide_enable)).assertExists()

        probe.window.fullscreenState = false
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription(getString(Res.string.fullscreen_hud_auto_hide_enable)).assertDoesNotExist()
    }

    @Test
    fun hidesAfterConfiguredTimeoutAndReturnsOnMove() = hudTest { probe ->
        enterFullscreen(probe)
        advanceBy(DELAY_SECONDS * 1000L - 2 * FRAME_MS)
        assertFalse(probe.hud.hidden.value)
        assertEquals(0f, probe.hud.progress.value)
        assertEquals(false, probe.window.cursor.last())

        hideCompletely(probe)

        moveMouse(Offset(300f, 300f))
        assertFalse(probe.hud.hidden.value)
        assertEquals(true, probe.window.cursor.last())
        advanceBy(HUD_SHOW_MILLIS + 2 * FRAME_MS)
        assertEquals(0f, probe.hud.progress.value)
    }

    @Test
    fun pointerEnteringWindowWhileHiddenBringsHudBack() = hudTest { probe ->
        enterFullscreen(probe)
        onRoot().performMouseInput { exit() }
        mainClock.advanceTimeByFrame()
        hideCompletely(probe)
        assertEquals(false, probe.window.cursor.last())

        onRoot().performMouseInput { enter(Offset(300f, 300f)) }
        mainClock.advanceTimeByFrame()
        assertFalse(probe.hud.hidden.value)
        assertEquals(true, probe.window.cursor.last())
        advanceBy(HUD_SHOW_MILLIS + 2 * FRAME_MS)
        assertEquals(0f, probe.hud.progress.value)
    }

    @Test
    fun neverHidesWithAutoHideOffOrOverlayOpen() = hudTest { probe ->
        probe.autoHide.value = false
        enterFullscreen(probe)
        advanceBy(DELAY_SECONDS * 3000L)
        assertFalse(probe.hud.hidden.value)
        assertEquals(false, probe.window.cursor.last())

        probe.autoHide.value = true
        probe.overlayOpen.value = true
        mainClock.advanceTimeByFrame()
        advanceBy(DELAY_SECONDS * 3000L)
        assertFalse(probe.hud.hidden.value)
        assertEquals(true, probe.window.cursor.last())
    }

    @Test
    fun transitionMovesDrawnPositionWithoutRecompositionOrRemeasure() = hudTest { probe ->
        enterFullscreen(probe)
        val compositions = probe.compositions
        val measures = probe.measures
        val lineCompositions = probe.lineCompositions
        val columnCenter = probe.lineColumnCenter()
        assertClose(Offset.Zero, probe.drawnOffset(), "shown")
        assertEquals(LINE_CURRENT_WIDTH, probe.lineWidth())
        assertClose(columnCenter, probe.lineCenter(), "line shown")

        advanceBy(DELAY_SECONDS * 1000L + 2 * FRAME_MS)
        assertTrue(probe.hud.hidden.value)

        val samples = mutableListOf<Float>()
        repeat(((HUD_HIDE_MILLIS / FRAME_MS) + 3).toInt()) {
            mainClock.advanceTimeByFrame()
            val p = probe.hud.progress.value
            samples += p
            assertClose(Offset(SHIFT_X * p, SHIFT_Y * p), probe.drawnOffset(), "drawn at progress $p")
            assertClose(probe.baseCenter.value + Offset(SHIFT_X * p, SHIFT_Y * p), probe.particleCenter.value, "particle center at progress $p")
            assertEquals(
                centeredWidth(LINE_CURRENT_WIDTH, hudLineWidth(LINE_CURRENT_WIDTH.toFloat(), LINE_FULL_WIDTH.toFloat(), p)),
                probe.lineWidth(),
                "line width at progress $p"
            )
            assertClose(columnCenter + Offset(SHIFT_X * p, LINE_SHIFT_Y * p), probe.lineCenter(), "line center at progress $p")
        }
        assertTrue(samples.any { it > 0f && it < 1f }, "no intermediate progress in $samples")
        assertEquals(samples.sorted(), samples)
        assertEquals(1f, samples.last())
        assertClose(Offset(SHIFT_X, SHIFT_Y), probe.drawnOffset(), "hidden")
        assertEquals(LINE_FULL_WIDTH, probe.lineWidth())
        assertClose(columnCenter + Offset(SHIFT_X, LINE_SHIFT_Y), probe.lineCenter(), "line hidden")

        moveMouse(Offset(250f, 250f))
        repeat(((HUD_SHOW_MILLIS / FRAME_MS) + 3).toInt()) {
            mainClock.advanceTimeByFrame()
            val p = probe.hud.progress.value
            assertClose(Offset(SHIFT_X * p, SHIFT_Y * p), probe.drawnOffset(), "drawn at progress $p")
        }
        assertClose(Offset.Zero, probe.drawnOffset(), "shown again")
        assertEquals(LINE_CURRENT_WIDTH, probe.lineWidth())
        assertClose(columnCenter, probe.lineCenter(), "line shown again")
        assertEquals(probe.baseCenter.value, probe.particleCenter.value)

        assertEquals(compositions, probe.compositions, "stand-in recomposed")
        assertEquals(measures, probe.measures, "stand-in remeasured")
        assertEquals(lineCompositions, probe.lineCompositions, "line stand-in recomposed")
    }

    @Test
    fun particleCenterFollowsLayoutCenterAndStaysUnspecified() = hudTest { probe ->
        enterFullscreen(probe)
        hideCompletely(probe)
        assertEquals(Offset(200f + SHIFT_X, 150f + SHIFT_Y), probe.particleCenter.value)

        probe.baseCenter.value = Offset(10f, 20f)
        assertEquals(Offset(10f + SHIFT_X, 20f + SHIFT_Y), probe.particleCenter.value)

        probe.baseCenter.value = Offset.Unspecified
        assertEquals(Offset.Unspecified, probe.particleCenter.value)
    }

    @Test
    fun firstClickWhileHiddenOnlyWakes() = hudTest { probe ->
        enterFullscreen(probe)
        hideCompletely(probe)

        onNodeWithContentDescription(CHROME).performMouseInput { click() }
        mainClock.advanceTimeByFrame()
        assertEquals(0, probe.chromeClicks)
        assertFalse(probe.hud.hidden.value)

        onNodeWithContentDescription(CHROME).performMouseInput { click() }
        mainClock.advanceTimeByFrame()
        assertEquals(1, probe.chromeClicks)
    }

    @Test
    fun leavingFullscreenRestoresHud() = hudTest { probe ->
        enterFullscreen(probe)
        hideCompletely(probe)
        assertEquals(false, probe.window.cursor.last())

        probe.window.fullscreenState = false
        mainClock.advanceTimeByFrame()
        assertFalse(probe.hud.hidden.value)
        assertEquals(true, probe.window.cursor.last())
        advanceBy(HUD_SHOW_MILLIS + 2 * FRAME_MS)
        assertEquals(0f, probe.hud.progress.value)
        assertClose(Offset.Zero, probe.drawnOffset(), "restored")
        onNodeWithContentDescription(getString(Res.string.fullscreen_hud_auto_hide_disable)).assertDoesNotExist()
    }

    private companion object {
        const val DELAY_SECONDS = 5
        const val FRAME_MS = 16L
        const val SHIFT_X = 40f
        const val SHIFT_Y = 31f
        const val CHROME = "hud-chrome"
        const val LINE_SHIFT_Y = 55f
        const val CONTENT_WIDTH = 400
        const val COLUMN_WIDTH = 200
        const val LINE_SCALE_WITH_PANEL = 0.95f
        const val LINE_SCALE_ALONE = 0.8f
        const val LINE_CURRENT_WIDTH = 190
        const val LINE_FULL_WIDTH = 320
    }
}
