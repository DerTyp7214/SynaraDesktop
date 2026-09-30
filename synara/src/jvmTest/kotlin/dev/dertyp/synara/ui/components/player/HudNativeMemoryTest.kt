package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.components.ParticleCanvas
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class HudNativeMemoryTest {
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

    @Test
    fun hudTransitionsKeepNativeMemoryFlat() {
        val random = Random(7)
        val x = FloatArray(count) { random.nextFloat() * width }
        val y = FloatArray(count) { random.nextFloat() * height }
        val life = FloatArray(count) { random.nextFloat() }
        val tick = mutableLongStateOf(0L)
        val hidden = mutableStateOf(false)
        val shift = mutableStateOf(HudShift(cover = Offset(24f, 31f), line = Offset(24f, 55f)))
        val center = mutableStateOf(Offset(width / 2f, height / 2f))
        var particleCenter = Offset.Unspecified
        var transitions = 0

        val scene = ImageComposeScene(width, height, Density(2f)) {
            val hud = rememberPlayerHud(hidden.value)
            val shiftedCenter = rememberHudShiftedCenter(center, hud.progress) { shift.value.cover }
            Box(modifier = Modifier.fillMaxSize()) {
                ParticleCanvas(
                    modifier = Modifier.fillMaxSize().drawBehind { particleCenter = shiftedCenter.value },
                    color = Color(0xFF3D2B6B).copy(alpha = .7f),
                    highlightColor = Color(0x8000E5FF),
                    particleX = x,
                    particleY = y,
                    particleLife = life,
                    count = { count },
                    tick = { tick.value }
                )
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .hudChrome(hud)
                            .background(Color(0x80FFFFFF))
                    )
                    Box(modifier = Modifier.size(64.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hudShift(hud.progress) { shift.value.cover }
                                .background(Color(0xFFFF2BD6), RoundedCornerShape(8.dp))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .hudLineWidth(hud.progress, 0.6f) { width * 0.9f }
                            .height(12.dp)
                            .hudShift(hud.progress) { shift.value.line }
                            .background(Color(0xFF00E5FF))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .hudChrome(hud)
                            .background(Color(0x80000000))
                    )
                }
            }
        }
        try {
            var time = 0L
            fun frames(n: Int) = repeat(n) { frame ->
                if (frame % CYCLE_FRAMES == 0) {
                    hidden.value = !hidden.value
                    transitions++
                }
                tick.value++
                time += 16_000_000L
                scene.render(time).close()
            }
            frames(WARMUP_FRAMES)
            val before = residentMegabytes()
            frames(FRAMES)
            val after = residentMegabytes()
            println("HUD native memory: rss $before -> $after MB over $FRAMES frames, $transitions transitions")
            assertTrue(transitions > FRAMES / CYCLE_FRAMES, "HUD did not cycle")
            assertTrue(particleCenter.x in (width / 2f)..(width / 2f + 24f), "particle center not drawn: $particleCenter")
            val grown = after - before
            assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WARMUP_FRAMES = 200
        const val FRAMES = 1_500
        const val CYCLE_FRAMES = 40
        const val MAX_GROWTH_MB = 50L
    }
}
