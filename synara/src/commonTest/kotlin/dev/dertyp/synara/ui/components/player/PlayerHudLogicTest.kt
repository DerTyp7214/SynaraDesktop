package dev.dertyp.synara.ui.components.player

import androidx.compose.ui.geometry.Offset
import dev.dertyp.synara.settings.FullscreenHudLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerHudLogicTest {

    @Test
    fun hiddenOnlyWhenEveryConditionHolds() {
        val flags = listOf(false, true)
        for (autoHide in flags) for (fullscreen in flags) for (expanded in flags) for (idle in flags) for (overlay in flags) {
            val expected = autoHide && fullscreen && expanded && idle && !overlay
            assertEquals(
                expected,
                playerHudHidden(autoHide, fullscreen, expanded, idle, overlay),
                "autoHide=$autoHide fullscreen=$fullscreen expanded=$expanded idle=$idle overlay=$overlay"
            )
        }
    }

    @Test
    fun circularVisualizerMovesCoverIntoFreedSpace() {
        val shift = hudShiftOffsets(strip = false, isRemote = false, height = 110f, topBar = 48f, panelWidth = 0f)
        assertEquals(Offset(0f, 31f), shift.cover)
        assertEquals(Offset(0f, 0f), shift.line)
    }

    @Test
    fun lineVisualizerMovesDownAndCoverStays() {
        val shift = hudShiftOffsets(strip = true, isRemote = false, height = 110f, topBar = 48f, panelWidth = 0f)
        assertEquals(Offset(0f, 0f), shift.cover)
        assertEquals(Offset(0f, 55f), shift.line)
    }

    @Test
    fun remotePlaybackCentersCoverLikeCircular() {
        val shift = hudShiftOffsets(strip = true, isRemote = true, height = 110f, topBar = 48f, panelWidth = 0f)
        assertEquals(Offset(0f, 31f), shift.cover)
        assertEquals(Offset(0f, 0f), shift.line)
    }

    @Test
    fun openPanelRecentersHorizontally() {
        val circular = hudShiftOffsets(strip = false, isRemote = false, height = 110f, topBar = 48f, panelWidth = 624f)
        assertEquals(Offset(312f, 31f), circular.cover)

        val line = hudShiftOffsets(strip = true, isRemote = false, height = 110f, topBar = 48f, panelWidth = 624f)
        assertEquals(Offset(312f, 0f), line.cover)
        assertEquals(Offset(312f, 55f), line.line)
    }

    @Test
    fun lineWidthGrowsFromCurrentToFullWithProgress() {
        assertEquals(190f, hudLineWidth(current = 190f, full = 320f, progress = 0f))
        assertEquals(255f, hudLineWidth(current = 190f, full = 320f, progress = 0.5f))
        assertEquals(320f, hudLineWidth(current = 190f, full = 320f, progress = 1f))
        assertEquals(300f, hudLineWidth(current = 300f, full = 300f, progress = 0.7f))
        assertEquals(190f, hudLineWidth(current = 190f, full = 0f, progress = 1f))
    }

    @Test
    fun centeredWidthKeepsEvenOverflowOnBothSides() {
        assertEquals(190, centeredWidth(190, 190f))
        assertEquals(204, centeredWidth(190, 203f))
        assertEquals(204, centeredWidth(190, 204.4f))
        assertEquals(320, centeredWidth(190, 320f))
        assertEquals(181, centeredWidth(191, 180f))
    }

    @Test
    fun sidePanelIgnoresTagsForPodcasts() {
        assertEquals(false, sidePanelShowing(queue = false, lyrics = false, tags = false, isPodcast = false))
        assertEquals(true, sidePanelShowing(queue = true, lyrics = false, tags = false, isPodcast = true))
        assertEquals(true, sidePanelShowing(queue = false, lyrics = true, tags = false, isPodcast = true))
        assertEquals(true, sidePanelShowing(queue = false, lyrics = false, tags = true, isPodcast = false))
        assertEquals(false, sidePanelShowing(queue = false, lyrics = false, tags = true, isPodcast = true))
    }

    @Test
    fun delayIsClampedToOneToThirtySeconds() {
        assertEquals(3, FullscreenHudLimits.DEFAULT_DELAY_SECONDS)
        assertEquals(1..30, FullscreenHudLimits.delaySeconds)
        assertEquals(1, FullscreenHudLimits.clampDelay(-5))
        assertEquals(1, FullscreenHudLimits.clampDelay(0))
        assertEquals(1, FullscreenHudLimits.clampDelay(1))
        assertEquals(3, FullscreenHudLimits.clampDelay(3))
        assertEquals(30, FullscreenHudLimits.clampDelay(30))
        assertEquals(30, FullscreenHudLimits.clampDelay(31))
    }
}
