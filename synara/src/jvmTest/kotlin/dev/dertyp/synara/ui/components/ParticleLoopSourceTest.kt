package dev.dertyp.synara.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticleLoopSourceTest {
    private val source = File("src/jvmMain/kotlin/dev/dertyp/synara/ui/components/ParticleViewGpu.jvm.kt")

    @Test
    fun particleLoopDoesNotWaitOnSnapshotFlow() {
        assertTrue(source.isFile, "Source not found at ${source.absolutePath}")
        val text = source.readText()
        assertTrue(text.contains("withFrameNanos"))
        assertFalse(text.contains("snapshotFlow"), "ParticleViewGpu must not resume its frame loop from a snapshotFlow")
    }
}
