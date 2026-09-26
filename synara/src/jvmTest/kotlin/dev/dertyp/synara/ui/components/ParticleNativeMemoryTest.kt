package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleNativeMemoryTest {
    private val width = 320
    private val height = 240
    private val count = 12_500

    private fun residentMegabytes(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val line = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
        return line.split(Regex("\\s+"))[1].toLong() / 1024
    }

    private fun growth(color: Color, highlight: Color, singleDraw: Boolean): Long {
        val random = Random(5)
        val x = FloatArray(count) { random.nextFloat() * width }
        val y = FloatArray(count) { random.nextFloat() * height }
        val life = FloatArray(count) { random.nextFloat() }
        val tick = mutableLongStateOf(0L)
        val paths = mutableSetOf<Boolean>()
        val scene = ImageComposeScene(width, height, Density(2f)) {
            ParticleCanvas(
                modifier = Modifier.fillMaxSize(),
                color = color,
                highlightColor = highlight,
                particleX = x,
                particleY = y,
                particleLife = life,
                count = { count },
                tick = { tick.value },
                onSingleDraw = { paths += it }
            )
        }
        try {
            var time = 0L
            fun frames(n: Int) = repeat(n) {
                tick.value++
                time += 16_000_000L
                scene.render(time).close()
            }
            frames(WARMUP_FRAMES)
            val before = residentMegabytes()
            frames(FRAMES)
            val after = residentMegabytes()
            assertEquals(setOf(singleDraw), paths)
            println("Particle native memory: single draw $singleDraw, rss $before -> $after MB over $FRAMES frames")
            return after - before
        } finally {
            scene.close()
        }
    }

    @Test
    fun singleDrawKeepsNativeMemoryFlat() {
        val grown = growth(Color(0xFFFF2BD6), Color(0xFF00E5FF), singleDraw = true)
        assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
    }

    @Test
    fun perBucketDrawKeepsNativeMemoryFlat() {
        val grown = growth(Color(0xFF3D2B6B).copy(alpha = .7f), Color(0x8000E5FF), singleDraw = false)
        assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
    }

    private companion object {
        const val WARMUP_FRAMES = 200
        const val FRAMES = 1_500
        const val MAX_GROWTH_MB = 50L
    }
}
