package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import dev.dertyp.services.models.*
import dev.dertyp.synara.ui.components.LyricsView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LyricsNativeMemoryTest {
    private val width = 480
    private val height = 720

    private fun residentMegabytes(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val line = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
        return line.split(Regex("\\s+"))[1].toLong() / 1024
    }

    private fun lyrics(): LyricsState.Loaded {
        val lines = (0 until LINES).flatMap { index ->
            val start = index * LINE_MS
            val words = (0 until 6).map { w ->
                LyricWord("syl${w} ", (start + w * WORD_MS).milliseconds, (start + (w + 1) * WORD_MS).milliseconds)
            }
            listOf(
                LyricLine(start.milliseconds, (start + LINE_MS).milliseconds, words, words.joinToString("") { it.text }, group = index),
                LyricLine(
                    (start + 200).milliseconds,
                    (start + 1200).milliseconds,
                    listOf(LyricWord("(ooh ", (start + 200).milliseconds, (start + 700).milliseconds), LyricWord("ah)", (start + 700).milliseconds, (start + 1200).milliseconds)),
                    "(ooh ah)",
                    background = true,
                    group = index
                )
            )
        }
        val candidate = LyricsCandidate(
            id = UUID.randomUUID(),
            songId = UUID.randomUUID(),
            provider = LyricsProviderInfo(id = "test", name = "Test", enabled = true, priority = 0),
            syncLevel = LyricsSyncLevel.WORD,
            instrumental = false,
            match = LyricsMatch.VERIFIED,
            durationGapSec = 0,
            rank = 1,
            preferred = false,
            fetchedAt = Date(0),
            plain = "",
            lyrics = SyncedLyrics(lines)
        )
        return LyricsState.Loaded(candidate, LyricsDisplay.from(candidate))
    }

    private fun brightPixels(image: Image): Int {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        image.readPixels(bitmap, 0, 0)
        val bytes = bitmap.readPixels() ?: return 0
        var count = 0
        for (i in bytes.indices step 4) {
            if ((bytes[i].toInt() and 0xFF) > 220) count++
        }
        bitmap.close()
        return count
    }

    @Test
    fun karaokeFramesKeepNativeMemoryFlat() {
        assumeTrue(File("/proc/self/status").exists())
        val state = lyrics()
        val position = MutableStateFlow(0L)
        val playing = MutableStateFlow(true)
        val seeks = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val scene = ImageComposeScene(width, height, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LyricsView(lyrics = state, position = position, isPlaying = playing, seekEvents = seeks, onSeek = {})
            }
        }
        try {
            var time = 0L
            var lit = 0
            fun frames(n: Int) = repeat(n) { frame ->
                time += 16_000_000L
                if (frame % REPORT_FRAMES == 0) position.value = time / 1_000_000L
                val image = scene.render(time)
                if (frame == n / 2) lit = maxOf(lit, brightPixels(image))
                image.close()
            }
            frames(WARMUP_FRAMES)
            val before = residentMegabytes()
            frames(FRAMES)
            val after = residentMegabytes()
            println("Lyrics native memory: rss $before -> $after MB over $FRAMES frames, lit $lit")
            assertTrue(lit > 0, "sweep was not drawn")
            val grown = after - before
            assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val LINES = 30
        const val LINE_MS = 1_800L
        const val WORD_MS = 300L
        const val WARMUP_FRAMES = 200
        const val FRAMES = 2_500
        const val REPORT_FRAMES = 5
        const val MAX_GROWTH_MB = 50L
    }
}
