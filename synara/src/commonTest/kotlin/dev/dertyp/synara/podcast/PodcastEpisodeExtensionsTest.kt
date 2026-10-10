package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastEpisodeExtensionsTest {

    private fun episode(
        durationMs: Long? = null,
        progress: PodcastEpisodeProgress? = null,
        id: PlatformUUID = randomPlatformUUID()
    ) = PodcastEpisode(
        id = id,
        showId = randomPlatformUUID(),
        showTitle = "Show",
        guid = "guid",
        title = "Episode",
        publishedAt = 0L,
        durationMs = durationMs,
        progress = progress,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun progress(
        positionMs: Long,
        completed: Boolean = false,
        durationMs: Long? = null,
        updatedAt: Long = 100L,
        episodeId: PlatformUUID = randomPlatformUUID()
    ) = PodcastEpisodeProgress(
        episodeId = episodeId,
        showId = randomPlatformUUID(),
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        lastPlayedAt = updatedAt,
        updatedAt = updatedAt
    )

    @Test
    fun startPositionIsZeroForAPlayedEpisode() {
        assertEquals(0L, episode(progress = progress(positionMs = 90_000, completed = true)).startPositionMs)
    }

    @Test
    fun startPositionIsTheStoredPositionOfAnUnfinishedEpisode() {
        assertEquals(90_000L, episode(progress = progress(positionMs = 90_000)).startPositionMs)
        assertEquals(0L, episode(progress = progress(positionMs = -500)).startPositionMs)
        assertEquals(0L, episode().startPositionMs)
    }

    @Test
    fun knownDurationPrefersTheEpisodeOverTheProgress() {
        assertEquals(5_000L, episode(durationMs = 5_000, progress = progress(positionMs = 0, durationMs = 9_000)).knownDurationMs)
        assertEquals(9_000L, episode(progress = progress(positionMs = 0, durationMs = 9_000)).knownDurationMs)
        assertNull(episode().knownDurationMs)
        assertNull(episode(progress = progress(positionMs = 0)).knownDurationMs)
    }

    @Test
    fun knownDurationRejectsNonPositiveValues() {
        assertNull(episode(durationMs = 0).knownDurationMs)
        assertNull(episode(durationMs = -1).knownDurationMs)
        assertNull(episode(progress = progress(positionMs = 0, durationMs = 0)).knownDurationMs)
        assertNull(episode(progress = progress(positionMs = 0, durationMs = -1)).knownDurationMs)
    }

    @Test
    fun remainingTimeIsClampedAtZero() {
        assertEquals(600L, episode(durationMs = 1_000, progress = progress(positionMs = 400)).remainingMs)
        assertEquals(0L, episode(durationMs = 1_000, progress = progress(positionMs = 5_000)).remainingMs)
        assertEquals(1_000L, episode(durationMs = 1_000).remainingMs)
        assertNull(episode(progress = progress(positionMs = 400)).remainingMs)
    }

    @Test
    fun progressFractionIsOneForAPlayedEpisode() {
        assertEquals(1f, episode(durationMs = 1_000, progress = progress(positionMs = 0, completed = true)).progressFraction)
        assertEquals(1f, episode(progress = progress(positionMs = 0, completed = true)).progressFraction)
    }

    @Test
    fun progressFractionIsZeroWithoutADuration() {
        assertEquals(0f, episode(progress = progress(positionMs = 400)).progressFraction)
        assertEquals(0f, episode(durationMs = 0, progress = progress(positionMs = 400)).progressFraction)
    }

    @Test
    fun progressFractionIsThePositionOverTheDurationWithinZeroAndOne() {
        assertEquals(0.5f, episode(durationMs = 1_000, progress = progress(positionMs = 500)).progressFraction)
        assertEquals(1f, episode(durationMs = 1_000, progress = progress(positionMs = 5_000)).progressFraction)
        assertEquals(0f, episode(durationMs = 1_000).progressFraction)
    }

    @Test
    fun blankHtmlBecomesAnEmptyString() {
        assertEquals("", htmlToPlainText(""))
        assertEquals("", htmlToPlainText("  \n\t "))
    }

    @Test
    fun textWithoutMarkupIsOnlyTrimmed() {
        assertEquals("plain  text\n\n\nsecond line", htmlToPlainText("  plain  text\n\n\nsecond line \n"))
        assertEquals("first\nsecond\nthird", htmlToPlainText("first\r\nsecond\rthird"))
    }

    @Test
    fun aLineBreakTagBecomesOneNewline() {
        assertEquals("first\nsecond", htmlToPlainText("first<br>second"))
        assertEquals("first\nsecond", htmlToPlainText("first<br/>second"))
        assertEquals("first\nsecond", htmlToPlainText("first<BR />second"))
    }

    @Test
    fun aClosingListItemBecomesOneNewline() {
        assertEquals("first\nsecond", htmlToPlainText("first</li>second"))
    }

    @Test
    fun paragraphsAndDivsAreSeparatedByABlankLine() {
        assertEquals("first\n\nsecond", htmlToPlainText("<p>first</p><p>second</p>"))
        assertEquals("first\n\nsecond", htmlToPlainText("<div class=\"note\">first</div><div>second</div>"))
    }

    @Test
    fun aListItemStartsWithABulletOnItsOwnLine() {
        assertEquals("intro\n• item", htmlToPlainText("intro<li>item"))
    }

    @Test
    fun theItemsOfAWellFormedListAreSeparatedByABlankLine() {
        assertEquals("• one\n\n• two", htmlToPlainText("<ul><li>one</li><li>two</li></ul>"))
    }

    @Test
    fun threeOrMoreNewlinesCollapseToABlankLine() {
        assertEquals("first\n\nsecond", htmlToPlainText("first<br><br><br><br>second"))
        assertEquals("first\n\nsecond", htmlToPlainText("<p>first</p><br><div><p>second</p></div>"))
    }

    @Test
    fun otherTagsAreRemoved() {
        assertEquals("bold and linked", htmlToPlainText("<b>bold</b> and <a href=\"https://example.org\">linked</a>"))
    }

    @Test
    fun numericEntitiesAreDecoded() {
        assertEquals("AB", htmlToPlainText("&#65;&#x42;"))
        assertEquals("ä €", htmlToPlainText("&#228; &#x20AC;"))
    }

    @Test
    fun numericEntitiesBeyondTheBasicPlaneAreDecodedToSurrogatePairs() {
        assertEquals("😀", htmlToPlainText("&#x1F600;"))
        assertEquals("😀", htmlToPlainText("&#128512;"))
    }

    @Test
    fun namedEntitiesAreDecoded() {
        val expected = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "bull" to "•",
            "middot" to "·",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "euro" to "€",
            "auml" to "ä",
            "ouml" to "ö",
            "uuml" to "ü",
            "Auml" to "Ä",
            "Ouml" to "Ö",
            "Uuml" to "Ü",
            "szlig" to "ß"
        )

        expected.forEach { (name, character) ->
            assertEquals("x${character}y", htmlToPlainText("x&$name;y"), name)
        }
    }

    @Test
    fun unknownEntitiesAreKept() {
        assertEquals("x&bogus;y", htmlToPlainText("x&bogus;y"))
        assertEquals("x&#x110000;y", htmlToPlainText("x&#x110000;y"))
        assertEquals("x & y", htmlToPlainText("x & y"))
    }

    @Test
    fun withProgressIgnoresAProgressOfAnotherEpisode() {
        val episode = episode()

        assertEquals(episode, episode.withProgress(progress(positionMs = 400)))
    }

    @Test
    fun withProgressSetsTheFirstProgress() {
        val episode = episode()
        val first = progress(positionMs = 400, episodeId = episode.id)

        assertEquals(first, episode.withProgress(first).progress)
    }

    @Test
    fun withProgressIgnoresAnOlderOrEquallyOldProgress() {
        val id = randomPlatformUUID()
        val current = progress(positionMs = 400, updatedAt = 100, episodeId = id)
        val episode = episode(progress = current, id = id)

        assertEquals(current, episode.withProgress(progress(positionMs = 900, updatedAt = 99, episodeId = id)).progress)
        assertEquals(current, episode.withProgress(progress(positionMs = 900, updatedAt = 100, episodeId = id)).progress)
    }

    @Test
    fun withProgressReplacesTheProgressWithANewerOne() {
        val id = randomPlatformUUID()
        val episode = episode(progress = progress(positionMs = 400, updatedAt = 100, episodeId = id), id = id)
        val newer = progress(positionMs = 900, updatedAt = 101, episodeId = id)

        val updated = episode.withProgress(newer)

        assertEquals(newer, updated.progress)
        assertEquals(episode.copy(progress = newer), updated)
    }

    @Test
    fun withProgressFromAMapAppliesOnlyTheEntryOfTheEpisode() {
        val first = episode()
        val second = episode()
        val entry = progress(positionMs = 400, episodeId = first.id)
        val map = mapOf(first.id to entry)

        assertEquals(entry, first.withProgress(map).progress)
        assertEquals(second, second.withProgress(map))
        assertEquals(listOf(first.copy(progress = entry), second), listOf(first, second).withProgress(map))
        assertEquals(listOf(first, second), listOf(first, second).withProgress(emptyMap()))
    }
}
