package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

class ParticleGoldenTest {
    private val goldenDir = File("src/jvmTest/resources/particle-goldens")
    private val diffDir = File("build/particle-golden-diffs")

    private val width = 320
    private val height = 240

    private class Particles(val x: FloatArray, val y: FloatArray, val life: FloatArray, val count: Int)

    private data class Colors(val name: String, val color: Color, val highlight: Color, val singleDraw: Boolean)

    private val playerColors = Colors("player", Color(0xFF3D2B6B).copy(alpha = .7f), Color(0xFF00E5FF), singleDraw = true)
    private val opaqueColors = Colors("opaque", Color(0xFFFF2BD6), Color(0xFF00E5FF), singleDraw = true)
    private val translucentColors = Colors("translucent", Color(0xFF3D2B6B).copy(alpha = .7f), Color(0x8000E5FF), singleDraw = false)
    private val faintColors = Colors("faint", Color(0x33FF2BD6), Color(0x4000E5FF), singleDraw = false)

    private fun sparse(seed: Int): Particles {
        val random = Random(seed)
        val count = 240
        val x = FloatArray(count)
        val y = FloatArray(count)
        val life = FloatArray(count)
        for (i in 0 until count) {
            x[i] = random.nextFloat() * (width + 40) - 20f
            y[i] = random.nextFloat() * (height + 40) - 20f
            life[i] = when (i % 12) {
                0 -> 1f
                1 -> 0f
                2 -> 0.999f
                3 -> -0.2f
                else -> (i % 100 + random.nextFloat()) / 100f
            }
        }
        return Particles(x, y, life, count)
    }

    private fun dense(seed: Int, count: Int): Particles {
        val random = Random(seed)
        val capacity = count + 17
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val life = FloatArray(capacity)
        for (i in 0 until count) {
            val cluster = i % 3
            val cx = width * (0.25f + cluster * 0.25f)
            val cy = height * (0.35f + cluster * 0.15f)
            x[i] = cx + (random.nextFloat() - 0.5f) * 90f
            y[i] = cy + (random.nextFloat() - 0.5f) * 70f
            life[i] = if (i % 7 == 0) (i % 100) / 100f else random.nextFloat()
        }
        return Particles(x, y, life, count)
    }

    private fun grid(): Particles {
        val columns = 40
        val rows = 30
        val count = columns * rows
        val x = FloatArray(count)
        val y = FloatArray(count)
        val life = FloatArray(count)
        for (i in 0 until count) {
            x[i] = (i % columns) * 8f + 4f + (i % 5) * 0.25f
            y[i] = (i / columns) * 8f + 4f + (i % 3) * 0.5f
            life[i] = (i % 101) / 100f
        }
        return Particles(x, y, life, count)
    }

    private data class Case(val name: String, val particles: () -> Particles, val density: Float, val colors: Colors)

    private fun cases(): List<Case> = buildList {
        val sets = listOf<Pair<String, () -> Particles>>(
            "sparse" to { sparse(7) },
            "grid" to { grid() },
            "dense" to { dense(11, 4000) },
            "chunked" to { dense(23, 12500) }
        )
        for ((setName, particles) in sets) {
            for (density in listOf(1f, 2f)) {
                for (colors in listOf(playerColors, opaqueColors, translucentColors)) {
                    add(Case("$setName-d${density.toInt()}-${colors.name}", particles, density, colors))
                }
            }
        }
        add(Case("sparse-d4-faint", { sparse(31) }, 4f, faintColors))
        add(Case("grid-d3-translucent", { grid() }, 3f, translucentColors))
        add(Case("chunked-d1-faint", { dense(41, 12500) }, 1f, faintColors))
    }

    private fun render(case: Case, paths: MutableSet<Boolean>): Image {
        val particles = case.particles()
        val scene = ImageComposeScene(width, height, Density(case.density)) {
            ParticleCanvas(
                modifier = Modifier.fillMaxSize(),
                color = case.colors.color,
                highlightColor = case.colors.highlight,
                particleX = particles.x,
                particleY = particles.y,
                particleLife = particles.life,
                count = { particles.count },
                tick = { 0L },
                onSingleDraw = { paths += it }
            )
        }
        try {
            scene.render(0L)
            return scene.render(16_000_000L)
        } finally {
            scene.close()
        }
    }

    private fun pixels(image: Image): ByteArray {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        image.readPixels(bitmap, 0, 0)
        return bitmap.readPixels() ?: error("no pixels")
    }

    private fun maxDifference(a: ByteArray, b: ByteArray): Int {
        var largest = 0
        for (i in a.indices) {
            val d = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            if (d > largest) largest = d
        }
        return largest
    }

    @Test
    fun rendersMatchGoldens() {
        val failures = mutableListOf<String>()
        val created = mutableListOf<String>()
        var worst = 0
        var singleCases = 0
        var fallbackCases = 0
        for (case in cases()) {
            val name = "${case.name}.png"
            val paths = mutableSetOf<Boolean>()
            val image = render(case, paths)
            if (paths != setOf(case.colors.singleDraw)) {
                failures += "$name: draw path $paths, expected single draw ${case.colors.singleDraw}"
            }
            if (case.colors.singleDraw) singleCases++ else fallbackCases++
            val encoded = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("encode failed for $name")
            val golden = File(goldenDir, name)
            if (!golden.exists()) {
                goldenDir.mkdirs()
                golden.writeBytes(encoded)
                created += name
                continue
            }
            val expected = Image.makeFromEncoded(golden.readBytes())
            if (expected.width != image.width || expected.height != image.height) {
                failures += "$name: size ${image.width}x${image.height} != ${expected.width}x${expected.height}"
                continue
            }
            val actual = Image.makeFromEncoded(encoded)
            val difference = maxDifference(pixels(expected), pixels(actual))
            worst = max(worst, difference)
            if (difference > MAX_CHANNEL_DIFFERENCE) {
                diffDir.mkdirs()
                File(diffDir, name).writeBytes(encoded)
                failures += "$name: max channel difference $difference"
            }
        }
        println("Particle goldens: worst channel difference $worst, created ${created.size}, single draw $singleCases, per bucket $fallbackCases")
        if (created.isNotEmpty()) fail("Created ${created.size} missing goldens, rerun to compare: ${created.joinToString()}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        const val MAX_CHANNEL_DIFFERENCE = 0
    }
}
