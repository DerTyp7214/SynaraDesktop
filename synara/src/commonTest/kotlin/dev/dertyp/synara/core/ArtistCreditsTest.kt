package dev.dertyp.synara.core

import dev.dertyp.data.Artist
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.Genre
import dev.dertyp.randomPlatformUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistCreditsTest {
    @Test
    fun artistCreditCarriesEveryField() {
        val genre = Genre(randomPlatformUUID(), "Rock")
        val artist = Artist(
            id = randomPlatformUUID(),
            name = "Name",
            isGroup = true,
            about = "Biography",
            genres = listOf(genre),
            imageId = randomPlatformUUID(),
            blurHash = "LEHV6nWB2yk8",
            musicBrainzId = randomPlatformUUID(),
            isFollowed = true,
            creditedName = "Credited",
            joinPhrase = " & ",
        )
        val expected = ArtistCredit(
            id = artist.id,
            name = "Name",
            isGroup = true,
            artists = emptyList(),
            genres = listOf(genre),
            imageId = artist.imageId,
            blurHash = "LEHV6nWB2yk8",
            musicBrainzId = artist.musicBrainzId,
            isFollowed = true,
            creditedName = "Credited",
            joinPhrase = " & ",
        )
        assertEquals(expected, artist.toCredit())
    }

    @Test
    fun artistCreditKeepsNullOptionalFields() {
        val artist = Artist(id = randomPlatformUUID(), name = "Solo", isGroup = false)
        val credit = artist.toCredit()
        assertEquals(artist.id, credit.id)
        assertEquals("Solo", credit.name)
        assertEquals(false, credit.isGroup)
        assertEquals(emptyList(), credit.artists)
        assertEquals(null, credit.imageId)
        assertEquals(null, credit.creditedName)
        assertEquals(null, credit.joinPhrase)
        assertEquals(false, credit.isFollowed)
    }

    @Test
    fun artistCreditConvertsMembersRecursively() {
        val grandchild = Artist(id = randomPlatformUUID(), name = "Grandchild", isGroup = false, joinPhrase = "x")
        val child = Artist(
            id = randomPlatformUUID(),
            name = "Child",
            isGroup = true,
            artists = listOf(grandchild),
            isFollowed = true,
        )
        val parent = Artist(id = randomPlatformUUID(), name = "Parent", isGroup = true, artists = listOf(child))
        val credit = parent.toCredit()
        assertEquals(listOf(child.id), credit.artists.map { it.id })
        assertEquals(true, credit.artists.single().isFollowed)
        val nested = credit.artists.single().artists.single()
        assertEquals(grandchild.id, nested.id)
        assertEquals("Grandchild", nested.name)
        assertEquals("x", nested.joinPhrase)
    }
}
