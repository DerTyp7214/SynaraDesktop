package dev.dertyp.synara.ui.components.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudSourceGuardTest {

    private val sources = listOf(
        "src/commonMain/kotlin/dev/dertyp/synara/SynaraView.kt",
        "src/commonMain/kotlin/dev/dertyp/synara/ui/components/PlayerBar.kt",
        "src/commonMain/kotlin/dev/dertyp/synara/ui/PointerIdle.kt",
        "src/commonMain/kotlin/dev/dertyp/synara/ui/components/player/PlayerHud.kt"
    )

    @Test
    fun hudWiringNeverCollectsSnapshotFlowsOrAwaitsFrames() {
        sources.forEach { path ->
            val file = File(path)
            assertTrue(file.isFile, "missing $path")
            val text = file.readText()
            assertFalse("snapshotFlow" in text, "$path uses snapshotFlow")
            assertFalse("withFrameNanos" in text, "$path uses withFrameNanos")
            assertFalse("withFrameMillis" in text, "$path uses withFrameMillis")
        }
    }
}
