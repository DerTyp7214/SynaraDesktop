package dev.dertyp.synara.ui.components

import dev.dertyp.data.Artist
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistCreditTextTest {
    private fun artist(name: String, joinPhrase: String? = null, creditedName: String? = null) =
        Artist(id = randomPlatformUUID(), name = name, isGroup = false, creditedName = creditedName, joinPhrase = joinPhrase)

    @Test
    fun joinPhrasesAreUsedInCreditOrder() {
        val artists = listOf(artist("Zed", " feat. "), artist("Alpha", " & "), artist("Mid"))
        assertEquals("Zed feat. Alpha & Mid", artists.creditText())
    }

    @Test
    fun missingJoinPhraseFallsBackToComma() {
        val artists = listOf(artist("A"), artist("B", " & "), artist("C"))
        assertEquals("A, B & C", artists.creditText())
    }

    @Test
    fun creditedNameIsPreferred() {
        val artists = listOf(artist("Canonical", " x ", creditedName = "Credited"), artist("Other"))
        assertEquals("Credited x Other", artists.creditText())
    }

    @Test
    fun lastJoinPhraseIsDropped() {
        val artists = listOf(artist("A", " & "), artist("B", " feat. "))
        assertEquals("A & B", artists.creditText())
    }

    @Test
    fun singleArtist() {
        assertEquals("Solo", listOf(artist("Solo", " & ", creditedName = "Solo")).creditText())
    }

    @Test
    fun emptyList() {
        assertEquals("", emptyList<Artist>().creditText())
    }
}
