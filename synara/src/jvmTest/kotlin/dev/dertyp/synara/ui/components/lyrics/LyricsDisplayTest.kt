package dev.dertyp.synara.ui.components.lyrics

import dev.dertyp.services.models.*
import dev.dertyp.synara.podcast.TranscriptCue
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LyricsDisplayTest {

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start.milliseconds, end.milliseconds)

    private fun line(
        start: Long,
        end: Long,
        words: List<LyricWord> = emptyList(),
        text: String? = null,
        voice: String? = null,
        background: Boolean = false,
        group: Int = 0
    ) = LyricLine(start.milliseconds, end.milliseconds, words, text, voice, background, group)

    private fun candidate(level: LyricsSyncLevel, plain: String, lyrics: SyncedLyrics?) = LyricsCandidate(
        id = UUID.randomUUID(),
        songId = UUID.randomUUID(),
        provider = LyricsProviderInfo(id = "test", name = "Test", enabled = true, priority = 0),
        syncLevel = level,
        instrumental = false,
        match = LyricsMatch.VERIFIED,
        durationGapSec = 0,
        rank = 1,
        preferred = false,
        fetchedAt = Date(0),
        plain = plain,
        lyrics = lyrics
    )

    @Test
    fun syllablesJoinWithoutSeparatorAndKeepOffsets() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(
                    1000, 2000,
                    words = listOf(word("Won", 1000, 1200), word("der", 1200, 1400), word("ful ", 1400, 1600), word("day", 1600, 2000)),
                    text = "Wonderful day"
                )
            )
        )
        val display = LyricsDisplay.fromSynced(synced, wordTimed = true)
        val result = display.lines.single()
        assertEquals("Wonderful day", result.text)
        val words = result.words!!
        assertEquals(listOf(0 to 3, 3 to 6, 6 to 10, 10 to 13), words.map { it.startIndex to it.endIndex })
        assertEquals(listOf("Won", "der", "ful ", "day"), words.map { result.text.substring(it.startIndex, it.endIndex) })
        assertEquals(1200L, words[1].start)
        assertEquals(1400L, words[1].end)
        assertTrue(display.wordTimed)
    }

    @Test
    fun wordsWithoutTextJoinWithSpaces() {
        val synced = SyncedLyrics(
            lines = listOf(line(0, 900, words = listOf(word("hello", 0, 400), word("there", 500, 900))))
        )
        val result = LyricsDisplay.fromSynced(synced, wordTimed = true).lines.single()
        assertEquals("hello there", result.text)
        assertEquals(listOf(0 to 5, 6 to 11), result.words!!.map { it.startIndex to it.endIndex })
    }

    @Test
    fun lineTimedLyricsCarryNoWords() {
        val synced = SyncedLyrics(
            lines = listOf(line(0, 900, words = listOf(word("hello", 0, 900)), text = "hello"))
        )
        val display = LyricsDisplay.fromSynced(synced, wordTimed = false)
        assertNull(display.lines.single().words)
        assertTrue(!display.wordTimed)
    }

    @Test
    fun backgroundLinesGoUnderTheirLead() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(0, 1000, text = "first", group = 0),
                line(1000, 2000, text = "second", group = 1),
                line(900, 1800, text = "(ooh)", background = true, group = 1),
                line(2000, 3000, text = "third", group = 2),
                line(3500, 4000, text = "(alone)", background = true, group = 9)
            )
        )
        val lines = LyricsDisplay.fromSynced(synced, wordTimed = false).lines
        assertEquals(listOf("first", "second", "third", "(alone)"), lines.map { it.text })
        assertEquals(listOf("(ooh)"), lines[1].backing.map { it.text })
        assertTrue(lines[1].backing.single().background)
        assertTrue(lines[0].backing.isEmpty())
        assertTrue(lines[3].background)
    }

    @Test
    fun duetVoicesGetIndexesAndGroupVoicesNone() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(0, 1000, text = "one", voice = "v1"),
                line(1000, 2000, text = "two", voice = "v2"),
                line(2000, 3000, text = "all", voice = "v3"),
                line(3000, 4000, text = "none")
            ),
            voices = mapOf(
                "v1" to LyricVoice("A"),
                "v2" to LyricVoice("B"),
                "v3" to LyricVoice("Both", LyricVoiceType.GROUP)
            )
        )
        val lines = LyricsDisplay.fromSynced(synced, wordTimed = false).lines
        assertEquals(listOf(0, 1, null, null), lines.map { it.voiceIndex })
    }

    @Test
    fun singleVoiceStaysCentered() {
        val synced = SyncedLyrics(
            lines = listOf(line(0, 1000, text = "one", voice = "v1"), line(1000, 2000, text = "two", voice = "v1")),
            voices = mapOf("v1" to LyricVoice("A"))
        )
        assertEquals(listOf(null, null), LyricsDisplay.fromSynced(synced, wordTimed = false).lines.map { it.voiceIndex })
    }

    @Test
    fun plainTextKeepsTwoSecondSpacing() {
        val display = LyricsDisplay.fromText("first\n\nsecond\nthird\n")
        assertEquals(listOf(0L, 2000L, 4000L), display.lines.map { it.start })
        assertEquals(listOf("first", "second", "third"), display.lines.map { it.text })
    }

    @Test
    fun lrcTextIsParsedAndSorted() {
        val display = LyricsDisplay.fromText("[ar:Someone]\n[00:12.50] later\n[00:01.250] early\n[00:20.00]\n")
        assertEquals(listOf(1250L, 12500L), display.lines.map { it.start })
        assertEquals(listOf("early", "later"), display.lines.map { it.text })
    }

    @Test
    fun plainCandidateUsesItsText() {
        val display = LyricsDisplay.from(candidate(LyricsSyncLevel.PLAIN, "a\nb", SyncedLyrics(listOf(line(0, 0, text = "ignored")))))
        assertEquals(listOf("a", "b"), display.lines.map { it.text })
        assertEquals(listOf(0L, 2000L), display.lines.map { it.start })
    }

    @Test
    fun wordCandidateUsesSyncedWords() {
        val synced = SyncedLyrics(listOf(line(500, 900, words = listOf(word("hi", 500, 900)), text = "hi")))
        val display = LyricsDisplay.from(candidate(LyricsSyncLevel.WORD, "hi", synced))
        assertEquals(500L, display.lines.single().start)
        assertEquals(1, display.lines.single().words!!.size)
    }

    @Test
    fun syncedCandidateWithoutContentFallsBackToPlain() {
        val display = LyricsDisplay.from(candidate(LyricsSyncLevel.LINE, "only plain", null))
        assertEquals(listOf("only plain"), display.lines.map { it.text })
    }

    private fun gaps(display: LyricsDisplay) = display.items.filterIsInstance<LyricsItem.Gap>().map { it.start to it.end }

    @Test
    fun syncedLyricsGetIntroAndInstrumentalGaps() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(8_600, 12_000, text = "first"),
                line(34_700, 37_100, text = "Now I'm lost in paradise"),
                line(136_200, 140_000, text = "Take a chance forget the old")
            )
        )
        val display = LyricsDisplay.fromSynced(synced, wordTimed = false)
        assertEquals(listOf(0L to 8_600L, 12_000L to 34_700L, 37_100L to 136_200L), gaps(display))
        assertEquals(6, display.items.size)
        assertEquals(display.lines, display.items.filterIsInstance<LyricsItem.Line>().map { it.line })
    }

    @Test
    fun gapsShorterThanFiveSecondsAreIgnored() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(4_999, 6_000, text = "one"),
                line(10_999, 12_000, text = "two"),
                line(17_000, 18_000, text = "three")
            )
        )
        val display = LyricsDisplay.fromSynced(synced, wordTimed = false)
        assertEquals(listOf(12_000L to 17_000L), gaps(display))
    }

    @Test
    fun backingThatEndsLaterShortensTheGap() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(0, 2_000, text = "lead", group = 0),
                line(1_000, 4_000, text = "(echo)", background = true, group = 0),
                line(8_500, 9_000, text = "close", group = 1),
                line(20_000, 21_000, text = "far", group = 2)
            )
        )
        val display = LyricsDisplay.fromSynced(synced, wordTimed = false)
        assertEquals(listOf(9_000L to 20_000L), gaps(display))
    }

    @Test
    fun overlappingEarlierLineKeepsTheGapClosed() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(0, 30_000, text = "long"),
                line(1_000, 2_000, text = "short"),
                line(26_000, 27_000, text = "inside")
            )
        )
        assertEquals(emptyList(), gaps(LyricsDisplay.fromSynced(synced, wordTimed = false)))
    }

    @Test
    fun activeItemFollowsGaps() {
        val synced = SyncedLyrics(
            lines = listOf(
                line(8_600, 12_000, text = "first"),
                line(34_700, 37_100, text = "lost"),
                line(38_000, 40_000, text = "close"),
                line(136_200, 140_000, text = "take")
            )
        )
        val items = LyricsDisplay.fromSynced(synced, wordTimed = false).items
        fun activeAt(time: Long) = items[activeItemAt(items, time)]
        assertTrue(activeAt(0) is LyricsItem.Gap)
        assertTrue(activeAt(8_599) is LyricsItem.Gap)
        assertEquals("first", (activeAt(8_600) as LyricsItem.Line).line.text)
        assertEquals("first", (activeAt(11_999) as LyricsItem.Line).line.text)
        assertEquals(LyricsItem.Gap(12_000, 34_700), activeAt(12_000))
        assertEquals("lost", (activeAt(37_500) as LyricsItem.Line).line.text)
        assertEquals("close", (activeAt(39_000) as LyricsItem.Line).line.text)
        assertEquals(LyricsItem.Gap(40_000, 136_200), activeAt(100_000))
        assertEquals("take", (activeAt(136_200) as LyricsItem.Line).line.text)
        assertEquals("take", (activeAt(500_000) as LyricsItem.Line).line.text)
    }

    @Test
    fun activeItemWithoutGapsMatchesLastStartedLine() {
        val items = LyricsDisplay.fromText("a\nb\nc").items
        assertEquals(0, activeItemAt(items, -10))
        assertEquals(0, activeItemAt(items, 1_999))
        assertEquals(1, activeItemAt(items, 2_000))
        assertEquals(2, activeItemAt(items, 60_000))
    }

    @Test
    fun plainLrcAndTranscriptsGetNoGaps() {
        val plain = LyricsDisplay.from(candidate(LyricsSyncLevel.PLAIN, "a\nb", SyncedLyrics(listOf(line(0, 0, text = "x"), line(60_000, 61_000, text = "y")))))
        val lrc = LyricsDisplay.fromText("[00:10.00] late\n[01:00.00] later\n")
        val lineFallback = LyricsDisplay.from(candidate(LyricsSyncLevel.LINE, "[00:30.00] only", null))
        val transcript = LyricsDisplay.fromTranscript(listOf(TranscriptCue(10_000, 11_000, null, "Hi"), TranscriptCue(60_000, 61_000, null, "There")))
        listOf(plain, lrc, lineFallback, transcript).forEach { display ->
            assertEquals(display.lines.map(LyricsItem::Line), display.items)
        }
    }

    @Test
    fun transcriptCuesKeepSpeakers() {
        val display = LyricsDisplay.fromTranscript(listOf(TranscriptCue(100, 900, "Host", "Hello"), TranscriptCue(1000, 1500, null, "Hi")))
        assertEquals(listOf(100L, 1000L), display.lines.map { it.start })
        assertEquals(listOf("Host", null), display.lines.map { it.label })
        assertEquals(listOf(900L, 1500L), display.lines.map { it.end })
    }
}
