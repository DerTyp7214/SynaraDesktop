package dev.dertyp.synara.ui.components.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dertyp.services.models.*
import dev.dertyp.synara.ui.components.LyricsView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalTestApi::class, ExperimentalComposeRuntimeApi::class)
class LyricsSweepTest {

    private class ScopeCounter : CompositionObserver {
        var entered = 0
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            entered++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start.milliseconds, end.milliseconds)

    private fun loaded(vararg lines: LyricLine): LyricsState.Loaded {
        val synced = SyncedLyrics(lines.toList())
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
            lyrics = synced
        )
        return LyricsState.Loaded(candidate, LyricsDisplay.from(candidate))
    }

    private val sung = LyricLine(
        startTime = 0.milliseconds,
        endTime = 8000.milliseconds,
        words = listOf(word("Sing ", 0, 2000), word("along ", 2000, 4000), word("with ", 4000, 6000), word("me", 6000, 8000)),
        text = "Sing along with me"
    )
    private val later = LyricLine(
        startTime = 20_000.milliseconds,
        endTime = 22_000.milliseconds,
        words = listOf(word("later", 20_000, 22_000)),
        text = "later"
    )

    private fun brightPixels(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].red > 0.85f) count++
            }
        }
        return count
    }

    @Test
    fun sweepAdvancesWithoutRecomposition() = runComposeUiTest {
        mainClock.autoAdvance = false
        val position = MutableStateFlow(0L)
        val playing = MutableStateFlow(true)
        val seeks = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val state = mutableStateOf<LyricsState>(loaded(sung, later))
        var composition: Composition? = null

        setContent {
            composition = currentComposer.composition
            MaterialTheme {
                Box(modifier = Modifier.size(900.dp, 700.dp).background(Color.Black)) {
                    LyricsView(
                        lyrics = state.value,
                        position = position,
                        isPlaying = playing,
                        seekEvents = seeks,
                        onSeek = {}
                    )
                }
            }
        }
        mainClock.advanceTimeBy(1000)
        val counter = ScopeCounter()
        runOnIdle { composition!!.setObserver(counter) }

        val early = brightPixels(onRoot().captureToImage())
        mainClock.advanceTimeBy(2000)
        val middle = brightPixels(onRoot().captureToImage())
        mainClock.advanceTimeBy(2000)
        val late = brightPixels(onRoot().captureToImage())

        assertTrue(early > 0, "nothing sung after one second")
        assertTrue(middle > early, "sweep did not advance: $early -> $middle")
        assertTrue(late > middle, "sweep did not advance: $middle -> $late")
        assertEquals(0, counter.entered, "sweep recomposed")

        runOnIdle { state.value = loaded(later) }
        mainClock.advanceTimeByFrame()
        assertTrue(counter.entered > 0, "observer did not see the recomposition")
    }

    @Test
    fun gapDotsFillWithoutRecomposition() = runComposeUiTest {
        mainClock.autoAdvance = false
        val position = MutableStateFlow(0L)
        val playing = MutableStateFlow(true)
        val seeks = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val lyrics = loaded(sung, later)
        var composition: Composition? = null

        setContent {
            composition = currentComposer.composition
            MaterialTheme {
                Box(modifier = Modifier.size(900.dp, 700.dp).background(Color.Black)) {
                    LyricsView(
                        lyrics = lyrics,
                        position = position,
                        isPlaying = playing,
                        seekEvents = seeks,
                        onSeek = {}
                    )
                }
            }
        }
        mainClock.advanceTimeBy(500)
        runOnIdle {
            position.value = 8_500L
            seeks.tryEmit(8_500L)
        }
        mainClock.advanceTimeBy(1000)
        val counter = ScopeCounter()
        runOnIdle { composition!!.setObserver(counter) }

        val early = brightPixels(onRoot().captureToImage())
        mainClock.advanceTimeBy(4000)
        val middle = brightPixels(onRoot().captureToImage())
        mainClock.advanceTimeBy(4000)
        val late = brightPixels(onRoot().captureToImage())

        assertTrue(middle > early, "dots did not fill: $early -> $middle")
        assertTrue(late > middle, "dots did not fill: $middle -> $late")
        assertEquals(0, counter.entered, "gap dots recomposed")
    }

    @Test
    fun seekMovesTheSweepAtOnce() = runComposeUiTest {
        mainClock.autoAdvance = false
        val position = MutableStateFlow(0L)
        val playing = MutableStateFlow(true)
        val seeks = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val lyrics = loaded(sung, later)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(900.dp, 700.dp).background(Color.Black)) {
                    LyricsView(
                        lyrics = lyrics,
                        position = position,
                        isPlaying = playing,
                        seekEvents = seeks,
                        onSeek = {}
                    )
                }
            }
        }
        mainClock.advanceTimeBy(500)
        val before = brightPixels(onRoot().captureToImage())
        runOnIdle {
            position.value = 7000L
            seeks.tryEmit(7000L)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val after = brightPixels(onRoot().captureToImage())
        assertTrue(after > before * 3, "seek did not move the sweep: $before -> $after")
    }
}
