package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.dertyp.services.models.LyricLine
import dev.dertyp.services.models.LyricWord
import dev.dertyp.services.models.SyncedLyrics
import org.jetbrains.skia.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class LyricsGoldenTest {
    private val goldenDir = File("src/jvmTest/resources/lyrics-goldens")
    private val diffDir = File("build/lyrics-golden-diffs")
    private val font = File("src/jvmTest/resources/fonts/DejaVuSans-Bold.ttf")

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start.milliseconds, end.milliseconds)

    private data class Case(val name: String, val line: DisplayLine, val time: Long)

    private fun cases(): List<Case> {
        val synced = SyncedLyrics(
            lines = listOf(
                LyricLine(
                    startTime = 1000.milliseconds,
                    endTime = 3000.milliseconds,
                    words = listOf(
                        word("Won", 1000, 1400),
                        word("der", 1400, 1800),
                        word("ful ", 1800, 2200),
                        word("world", 2200, 3000)
                    ),
                    text = "Wonderful world",
                    group = 0
                ),
                LyricLine(
                    startTime = 1200.milliseconds,
                    endTime = 2400.milliseconds,
                    words = listOf(word("(oh ", 1200, 1700), word("yeah)", 1700, 2400)),
                    text = "(oh yeah)",
                    background = true,
                    group = 0
                )
            )
        )
        val line = LyricsDisplay.fromSynced(synced, wordTimed = true).lines.single()
        return listOf(Case("half-sung-syllable", line, 1600L))
    }

    private fun render(case: Case): Image {
        val position = mutableLongStateOf(case.time)
        val style = TextStyle(
            fontFamily = FontFamily(Font(font, FontWeight.Bold)),
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center
        )
        val scene = ImageComposeScene(480, 160, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                LyricsRow(line = case.line, position = position, active = true, style = style, onSeek = {})
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
            val name = "${case.name}.png"
            val image = render(case)
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
        println("Lyrics goldens: worst channel difference $worst, created ${created.size}")
        if (created.isNotEmpty()) fail("Created ${created.size} missing goldens, rerun to compare: ${created.joinToString()}")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        const val MAX_CHANNEL_DIFFERENCE = 1
    }
}
