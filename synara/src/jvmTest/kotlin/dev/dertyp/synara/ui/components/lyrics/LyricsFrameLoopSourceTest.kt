package dev.dertyp.synara.ui.components.lyrics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsFrameLoopSourceTest {
    private val clock = File("src/commonMain/kotlin/dev/dertyp/synara/ui/components/lyrics/LyricsClock.kt")
    private val others = listOf(
        "src/commonMain/kotlin/dev/dertyp/synara/ui/components/LyricsView.kt",
        "src/commonMain/kotlin/dev/dertyp/synara/ui/components/lyrics/LyricsRow.kt"
    )

    @Test
    fun frameLoopLivesOnlyInTheClockWithoutSnapshotFlow() {
        assertTrue(clock.isFile, "Source not found at ${clock.absolutePath}")
        val text = clock.readText()
        assertTrue(text.contains("withFrameNanos"))
        assertFalse(text.contains("snapshotFlow"), "the lyrics clock must not resume its frame loop from a snapshotFlow")
        others.forEach { path ->
            val file = File(path)
            assertTrue(file.isFile, "missing $path")
            val other = file.readText()
            assertFalse("withFrameNanos" in other, "$path awaits frames")
            assertFalse("withFrameMillis" in other, "$path awaits frames")
        }
    }
}
