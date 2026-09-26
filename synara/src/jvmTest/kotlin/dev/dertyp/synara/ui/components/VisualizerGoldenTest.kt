package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.dertyp.synara.player.StereoSpectrum
import dev.dertyp.synara.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.*
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.fail

class VisualizerGoldenTest {
    private val goldenDir = File("src/jvmTest/resources/visualizer-goldens")
    private val diffDir = File("build/visualizer-golden-diffs")

    private val source = VisualizerSource(
        fftData = MutableStateFlow(FloatArray(512)),
        stereoFftData = MutableStateFlow(StereoSpectrum.EMPTY),
        isPlaying = MutableStateFlow(false),
        sampleRate = MutableStateFlow(44100)
    )

    private val colors = VisualizerColors(base = Color(0xFF3D2B6B), highlight = Color(0xFF00E5FF))
    private val neonColors = VisualizerColors(base = Color(0xFFFF2BD6), highlight = Color(0xFF00E5FF))
    private val translucentColors = VisualizerColors(base = Color(0x803D2B6B), highlight = Color(0xCC00E5FF))

    private data class Pattern(val name: String, val snapshot: VisualizerSnapshot)

    private fun wrapped(i: Int, n: Int): Float = if (n > 1) i / (n - 1f) else 0f

    private fun hash(i: Int, seed: Int): Float {
        var x = i * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    private val smooth = Pattern(
        "smooth",
        VisualizerSnapshot(
            tick = 37L,
            height = { i, n -> (0.5f + 0.35f * sin(wrapped(i, n) * 2f * PI.toFloat() * 2.3f) + 0.1f * sin(i * 0.9f)).coerceIn(0.02f, 1f) },
            overlayHeight = { i, n -> (0.45f + 0.4f * sin(wrapped(i, n) * 2f * PI.toFloat() * 1.4f + 1.1f)).coerceIn(0.02f, 1f) }
        )
    )

    private val spiky = Pattern(
        "spiky",
        VisualizerSnapshot(
            tick = 113L,
            height = { i, _ -> if (hash(i, 3) > 0.85f) 1f else hash(i, 1) * 0.9f + 0.03f },
            overlayHeight = { i, _ -> hash(i, 2) * 0.95f + 0.02f }
        )
    )

    private val burning = Pattern(
        "burning",
        VisualizerSnapshot(
            tick = 71L,
            rotation = 23f,
            height = { i, n -> if (abs(wrapped(i, n) - 0.3f) < 0.08f || abs(wrapped(i, n) - 0.75f) < 0.05f) 1f else 0.25f + 0.5f * hash(i, 5) },
            overlayHeight = { i, n -> if (abs(wrapped(i, n) - 0.5f) < 0.06f) 1f else 0.2f + 0.4f * hash(i, 6) },
            flame = { i, n ->
                val p = wrapped(i, n)
                max(max(1f - abs(p - 0.3f) / 0.1f, 1f - abs(p - 0.75f) / 0.07f), 1f - abs(p - 0.5f) / 0.08f).coerceIn(0f, 1f)
            }
        )
    )

    private data class Case(val name: String, val preset: VisualizerPreset, val colors: VisualizerColors, val patterns: List<Pattern>)

    private fun strip(name: String, patterns: List<Pattern> = listOf(smooth, spiky), colors: VisualizerColors = this.colors, block: VisualizerPreset.() -> VisualizerPreset) =
        Case(name, VisualizerPreset(id = name, name = name).block(), colors, patterns)

    private fun radial(name: String, patterns: List<Pattern> = listOf(smooth, spiky), colors: VisualizerColors = this.colors, block: VisualizerPreset.() -> VisualizerPreset) =
        Case(name, VisualizerPreset(id = name, name = name, shape = VisualizerShape.Radial).block(), colors, patterns)

    private val wave = VisualizerRenderMode.Wave

    private fun cases(): List<Case> = buildList {
        VisualizerPresets.builtIns.forEach { preset ->
            val presetColors = if (preset.id == VisualizerPresets.NEON_ID) neonColors else colors
            add(Case("preset-${preset.id.substringAfter('.')}", preset, presetColors, listOf(smooth, spiky, burning)))
        }
        add(strip("strip-bars-height") { copy(glowEnabled = false, flameEnabled = false) })
        add(strip("strip-bars-across") { copy(fillMode = VisualizerFillMode.GradientAcross, glowEnabled = false) })
        add(strip("strip-bars-vertical", listOf(smooth, burning)) { copy(fillMode = VisualizerFillMode.Vertical) })
        add(strip("strip-bars-vertical-bottom") { copy(fillMode = VisualizerFillMode.Vertical, anchor = VisualizerAnchor.Bottom, glowEnabled = false) })
        add(strip("strip-bars-vertical-top") { copy(fillMode = VisualizerFillMode.Vertical, anchor = VisualizerAnchor.Top, glowEnabled = false) })
        add(strip("strip-bars-overlay", listOf(smooth, burning)) { copy(mirrored = false, anchor = VisualizerAnchor.Bottom) })
        add(strip("strip-bars-overlay-vertical") { copy(mirrored = false, fillMode = VisualizerFillMode.Vertical, anchor = VisualizerAnchor.Top) })
        add(strip("strip-bars-flames-center", listOf(burning)) { copy(glowEnabled = false) })
        add(strip("strip-bars-flames-bottom", listOf(burning)) { copy(anchor = VisualizerAnchor.Bottom, flameIntensity = 1.6f) })
        add(strip("strip-bars-flames-top", listOf(burning)) { copy(anchor = VisualizerAnchor.Top, glowEnabled = false) })
        add(strip("strip-bars-gap0") { copy(barGap = 0f, cornerRadius = 0f) })
        add(strip("strip-bars-translucent", colors = translucentColors) { copy(opacity = 0.7f) })
        add(strip("strip-wave-height") { copy(renderMode = wave, glowEnabled = false) })
        add(strip("strip-wave-height-glow", listOf(smooth, spiky, burning)) { copy(renderMode = wave) })
        add(strip("strip-wave-across") { copy(renderMode = wave, fillMode = VisualizerFillMode.GradientAcross) })
        add(strip("strip-wave-vertical") { copy(renderMode = wave, fillMode = VisualizerFillMode.Vertical, glowEnabled = false) })
        add(strip("strip-wave-vertical-bottom") { copy(renderMode = wave, fillMode = VisualizerFillMode.Vertical, anchor = VisualizerAnchor.Bottom) })
        add(strip("strip-wave-nostroke") { copy(renderMode = wave, waveStroke = 0f, glowEnabled = false) })
        add(strip("strip-wave-strokeonly") { copy(renderMode = wave, waveFill = false, waveStroke = 3f) })
        add(strip("strip-wave-strokeonly-across") { copy(renderMode = wave, waveFill = false, fillMode = VisualizerFillMode.GradientAcross) })
        add(strip("strip-wave-overlay", listOf(smooth, spiky, burning)) { copy(renderMode = wave, mirrored = false, anchor = VisualizerAnchor.Bottom) })
        add(strip("strip-wave-overlay-across") { copy(renderMode = wave, mirrored = false, fillMode = VisualizerFillMode.GradientAcross) })
        add(strip("strip-wave-overlay-vertical") { copy(renderMode = wave, mirrored = false, fillMode = VisualizerFillMode.Vertical, glowEnabled = false) })
        add(strip("strip-wave-flames-center", listOf(burning)) { copy(renderMode = wave, glowEnabled = false) })
        add(strip("strip-wave-flames-bottom", listOf(burning)) { copy(renderMode = wave, anchor = VisualizerAnchor.Bottom) })
        add(strip("strip-wave-flames-top", listOf(burning)) { copy(renderMode = wave, anchor = VisualizerAnchor.Top, flameIntensity = 1.6f) })
        add(strip("strip-wave-gap0") { copy(renderMode = wave, barGap = 0f) })
        add(strip("strip-wave-translucent", colors = translucentColors) { copy(renderMode = wave, opacity = 0.8f) })
        add(radial("radial-bars-height") { copy(glowEnabled = false) })
        add(radial("radial-bars-glow") { copy() })
        add(radial("radial-bars-across") { copy(fillMode = VisualizerFillMode.GradientAcross) })
        add(radial("radial-bars-vertical") { copy(fillMode = VisualizerFillMode.Vertical, glowEnabled = false) })
        add(radial("radial-bars-overlay") { copy(mirrored = false) })
        add(radial("radial-bars-overlay-vertical") { copy(mirrored = false, fillMode = VisualizerFillMode.Vertical, glowEnabled = false) })
        add(radial("radial-bars-flames", listOf(burning)) { copy() })
        add(radial("radial-bars-gap0") { copy(barGap = 0f, glowEnabled = false, radialRoundness = 0.4f) })
        add(radial("radial-wave-height") { copy(renderMode = wave, glowEnabled = false) })
        add(radial("radial-wave-height-glow", listOf(smooth, spiky, burning)) { copy(renderMode = wave) })
        add(radial("radial-wave-across") { copy(renderMode = wave, fillMode = VisualizerFillMode.GradientAcross) })
        add(radial("radial-wave-vertical") { copy(renderMode = wave, fillMode = VisualizerFillMode.Vertical) })
        add(radial("radial-wave-nostroke") { copy(renderMode = wave, waveStroke = 0f, glowEnabled = false) })
        add(radial("radial-wave-strokeonly") { copy(renderMode = wave, waveFill = false, waveStroke = 3f) })
        add(radial("radial-wave-overlay", listOf(smooth, spiky, burning)) { copy(renderMode = wave, mirrored = false) })
        add(radial("radial-wave-overlay-vertical") { copy(renderMode = wave, mirrored = false, fillMode = VisualizerFillMode.Vertical) })
        add(radial("radial-wave-flames", listOf(burning)) { copy(renderMode = wave, glowEnabled = false) })
        add(radial("radial-wave-rotated", listOf(burning)) { copy(renderMode = wave, radialAngle = 47f, radialRoundness = 0.3f) })
        add(radial("radial-wave-gap0") { copy(renderMode = wave, barGap = 0f, radialInnerPadding = 0f) })
        add(radial("radial-wave-translucent", colors = translucentColors) { copy(renderMode = wave, opacity = 0.75f) })
    }

    private fun render(case: Case, pattern: Pattern): Image {
        val radial = case.preset.shape == VisualizerShape.Radial
        val width = if (radial) 300 else 480
        val height = if (radial) 300 else 120
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalVisualizerSnapshot provides pattern.snapshot) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VisualizerView(preset = case.preset, colors = case.colors, modifier = Modifier.fillMaxSize(), source = source)
                }
            }
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
        for (case in cases()) {
            for (pattern in case.patterns) {
                val name = "${case.name}-${pattern.name}.png"
                val image = render(case, pattern)
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
        }
        println("Visualizer goldens: worst channel difference $worst, created ${created.size}")
        if (created.isNotEmpty()) fail("Created ${created.size} missing goldens, rerun to compare: ${created.joinToString()}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        const val MAX_CHANNEL_DIFFERENCE = 1
    }
}
