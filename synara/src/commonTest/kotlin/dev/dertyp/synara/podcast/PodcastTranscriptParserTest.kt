package dev.dertyp.synara.podcast

import dev.dertyp.data.PodcastTranscript
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastTranscriptParserTest {

    @Test
    fun parsesVttWithCueIdsSpeakersAndMarkup() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE this is a comment

            intro-1
            00:00:01.000 --> 00:00:04.500 align:start
            <v Alice>Hello and <b>welcome</b> to the show.

            00:04.500 --> 00:07.250
            <v.host Bob>Thanks &amp; hi
            everyone!

            3
            01:00:00.000 --> 01:00:02.000
            <c.yellow>Plain</c> <00:00:01.500>line
        """.trimIndent()

        val result = PodcastTranscriptParser.parse(vtt, "text/vtt")

        assertTrue(result.timed)
        assertEquals(3, result.cues.size)
        assertEquals(TranscriptCue(1_000, 4_500, "Alice", "Hello and welcome to the show."), result.cues[0])
        assertEquals(TranscriptCue(4_500, 7_250, "Bob", "Thanks & hi everyone!"), result.cues[1])
        assertEquals(3_600_000, result.cues[2].startMs)
        assertNull(result.cues[2].speaker)
        assertEquals("Plain line", result.cues[2].text)
    }

    @Test
    fun parsesSrt() {
        val srt = "1\r\n00:00:00,500 --> 00:00:02,000\r\nFirst <i>line</i>\r\n\r\n" +
            "2\r\n00:00:02,000 --> 00:00:05,120\r\nSecond\r\nline\r\n"

        val result = PodcastTranscriptParser.parse(srt, "application/srt")

        assertTrue(result.timed)
        assertEquals(
            listOf(
                TranscriptCue(500, 2_000, null, "First line"),
                TranscriptCue(2_000, 5_120, null, "Second line")
            ),
            result.cues
        )
    }

    @Test
    fun detectsSrtDeclaredAsPlainText() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        val result = PodcastTranscriptParser.parse(srt, "text/plain")
        assertTrue(result.timed)
        assertEquals(1_000, result.cues.single().startMs)
    }

    @Test
    fun parsesSentenceLevelJson() {
        val json = """
            {"version":"1.0.0","segments":[
              {"speaker":"Alice","startTime":0.5,"endTime":3.25,"body":"This is the first sentence of the episode"},
              {"speaker":"Bob","startTime":3.3,"endTime":6,"body":"And this is a reply from the second host"}
            ]}
        """.trimIndent()

        val result = PodcastTranscriptParser.parse(json, "application/json")

        assertTrue(result.timed)
        assertEquals(
            listOf(
                TranscriptCue(500, 3_250, "Alice", "This is the first sentence of the episode"),
                TranscriptCue(3_300, 6_000, "Bob", "And this is a reply from the second host")
            ),
            result.cues
        )
    }

    @Test
    fun mergesWordLevelJsonIntoSentences() {
        val words = listOf(
            Triple("Alice", 0.0, "Hello"),
            Triple(null, 0.4, "there"),
            Triple(null, 0.8, "."),
            Triple(null, 1.2, "How"),
            Triple(null, 1.5, "are"),
            Triple(null, 1.8, "you"),
            Triple("Bob", 2.2, "Fine"),
            Triple(null, 2.6, "thanks"),
            Triple(null, 5.0, "Later"),
            Triple(null, 5.3, "words!")
        )
        val segments = words.joinToString(",") { (speaker, start, body) ->
            val speakerField = if (speaker != null) "\"speaker\":\"$speaker\"," else ""
            "{$speakerField\"startTime\":$start,\"endTime\":${start + 0.3},\"body\":\"$body\"}"
        }
        val json = "{\"segments\":[$segments]}"

        val result = PodcastTranscriptParser.parse(json, "application/json")

        assertTrue(result.timed)
        assertEquals(
            listOf(
                TranscriptCue(0, 1_100, "Alice", "Hello there."),
                TranscriptCue(1_200, 2_100, "Alice", "How are you"),
                TranscriptCue(2_200, 2_900, "Bob", "Fine thanks"),
                TranscriptCue(5_000, 5_600, "Bob", "Later words!")
            ),
            result.cues
        )
    }

    @Test
    fun splitsLongWordRuns() {
        val segments = (0 until 120).joinToString(",") { i ->
            "{\"startTime\":${i * 0.2},\"endTime\":${i * 0.2 + 0.1},\"body\":\"word\"}"
        }
        val result = PodcastTranscriptParser.parse("{\"segments\":[$segments]}", "application/json")

        assertTrue(result.cues.size > 1)
        assertTrue(result.cues.all { it.text.length <= 210 })
        assertEquals(120, result.cues.sumOf { it.text.split(' ').size })
    }

    @Test
    fun parsesPlainTextAsUntimedParagraphs() {
        val text = "First paragraph\ncontinues here.\n\n\nSecond paragraph."

        val result = PodcastTranscriptParser.parse(text, "text/plain")

        assertFalse(result.timed)
        assertEquals(listOf("First paragraph\ncontinues here.", "Second paragraph."), result.cues.map { it.text })
    }

    @Test
    fun parsesHtmlAsUntimedText() {
        val html = "<p>Hello <b>world</b></p><p>Second &amp; last</p>"

        val result = PodcastTranscriptParser.parse(html, "text/html")

        assertFalse(result.timed)
        assertEquals(listOf("Hello world", "Second & last"), result.cues.map { it.text })
    }

    @Test
    fun brokenTimedTranscriptFallsBackToPlain() {
        val result = PodcastTranscriptParser.parse("not json at all", "application/json")
        assertFalse(result.timed)
        assertEquals("not json at all", result.cues.single().text)
    }

    @Test
    fun ranksFormatsAndPicksLanguage() {
        val episodeId = randomPlatformUUID()
        fun transcript(type: String, language: String?) =
            PodcastTranscript(randomPlatformUUID(), episodeId, type, language, null, true)

        val plainEn = transcript("text/plain", "en")
        val srtEn = transcript("application/x-subrip", "en")
        val jsonDe = transcript("application/json", "de")
        val vttEn = transcript("text/vtt", "en")
        val all = listOf(plainEn, srtEn, jsonDe, vttEn)

        assertEquals(listOf(jsonDe, vttEn, srtEn, plainEn), PodcastTranscriptParser.ranked(all))
        assertEquals(listOf("de", "en"), PodcastTranscriptParser.languages(all))
        assertEquals("en", PodcastTranscriptParser.preferredLanguage(all, "en"))
        assertEquals("de", PodcastTranscriptParser.preferredLanguage(all, "fr"))
        assertEquals(listOf(vttEn, srtEn, plainEn), PodcastTranscriptParser.candidates(all, "en"))
    }

    @Test
    fun parsesTimestamps() {
        assertEquals(3_723_045, PodcastTranscriptParser.parseTimestamp("01:02:03.045"))
        assertEquals(63_500, PodcastTranscriptParser.parseTimestamp("01:03,5"))
        assertEquals(7_000, PodcastTranscriptParser.parseTimestamp("7"))
        assertNull(PodcastTranscriptParser.parseTimestamp("abc"))
    }
}
