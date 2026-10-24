package dev.dertyp.synara.core

import dev.dertyp.core.withTitleTags
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.Genre
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.data.Song
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.services.models.LyricsSyncLevel
import dev.dertyp.randomPlatformUUID
import dev.dertyp.toPlatformLocalDateISO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleTagsDisplayTest {
    private val feat = TitleTag(TitleTagKind.FEAT, "feat. Someone")
    private val remix = TitleTag(TitleTagKind.REMIX, "Club Remix")
    private val live = TitleTag(TitleTagKind.LIVE, "Live at Home")

    private fun song(title: String = "Title", tags: List<TitleTag> = emptyList()) = Song(
        id = randomPlatformUUID(),
        title = title,
        artists = emptyList(),
        album = null,
        duration = 1_000,
        explicit = false,
        path = "/song",
        tags = tags,
    )

    private fun album(name: String = "Album", tags: List<TitleTag> = emptyList()) = Album(
        id = randomPlatformUUID(),
        name = name,
        artists = emptyList(),
        releaseDate = null,
        totalDuration = 0,
        tags = tags,
    )

    @Test
    fun displayTagsDropFeaturedArtistTags() {
        assertEquals(listOf(remix, live), listOf(feat, remix, live).displayTags)
    }

    @Test
    fun displayTagsOfOnlyFeatIsEmpty() {
        assertEquals(emptyList(), listOf(feat).displayTags)
    }

    @Test
    fun displayTitleAppendsTheDisplayTags() {
        val song = song(tags = listOf(feat, remix))
        assertEquals(song.title.withTitleTags(song.tags.displayTags), song.displayTitle)
    }

    @Test
    fun displayTitleWithoutTagsIsTheTitle() {
        assertEquals("Title", song().displayTitle)
    }

    @Test
    fun displayTitleWithOnlyFeatIsTheTitle() {
        assertEquals("Title", song(tags = listOf(feat)).displayTitle)
    }

    @Test
    fun textTitleWithoutTagsIsTheRawTitle() {
        assertEquals("Title", song(tags = listOf(remix)).textTitle(false))
    }

    @Test
    fun textTitleWithTagsIsTheDisplayTitle() {
        val song = song(tags = listOf(feat, remix, live))
        assertEquals(song.displayTitle, song.textTitle(true))
    }

    @Test
    fun displayNameAppendsTheDisplayTags() {
        val album = album(tags = listOf(feat, live))
        assertEquals(album.name.withTitleTags(album.tags.displayTags), album.displayName)
    }

    @Test
    fun displayNameWithoutTagsIsTheName() {
        assertEquals("Album", album().displayName)
    }

    @Test
    fun textNameWithoutTagsIsTheRawName() {
        assertEquals("Album", album(tags = listOf(live)).textName(false))
    }

    @Test
    fun textNameWithTagsIsTheDisplayName() {
        val album = album(tags = listOf(live))
        assertEquals(album.displayName, album.textName(true))
    }

    @Test
    fun selectableKindsExcludeUnknown() {
        assertFalse(TitleTagKind.UNKNOWN in selectableTitleTagKinds)
    }

    @Test
    fun selectableKindsContainEveryOtherKind() {
        assertEquals(TitleTagKind.entries.filter { it != TitleTagKind.UNKNOWN }, selectableTitleTagKinds)
        assertTrue(TitleTagKind.FEAT in selectableTitleTagKinds)
    }

    @Test
    fun userSongConvertsToSongWithEverySharedField() {
        val artist = ArtistCredit(id = randomPlatformUUID(), name = "Artist", isGroup = false)
        val userSong = UserSong(
            id = randomPlatformUUID(),
            title = "Title",
            artists = listOf(artist),
            album = album(),
            duration = 123_456,
            explicit = true,
            releaseDate = "2020-02-03".toPlatformLocalDateISO(),
            lyricsInfo = SongLyricsInfo(available = true, syncLevel = LyricsSyncLevel.LINE, levels = setOf(LyricsSyncLevel.LINE)),
            path = "/music/song.flac",
            originalUrl = "https://example.org/song",
            trackNumber = 7,
            discNumber = 2,
            copyright = "(c) someone",
            audio = AudioInfo("flac", 44_100, 16, 900_000, 5_000_000, 2),
            atmos = AudioInfo("eac3", 48_000, 0, 768_000, 17_000_000, 6),
            coverId = randomPlatformUUID(),
            blurHash = "LEHV6nWB2yk8",
            musicBrainzId = randomPlatformUUID(),
            isrc = "USRC17607839",
            genres = listOf(Genre(randomPlatformUUID(), "Rock")),
            animatedCoverId = randomPlatformUUID(),
            animatedCoverImageId = randomPlatformUUID(),
            animatedCoverBlurHash = "L00000",
            audioStartMs = 1_500,
            tags = listOf(remix),
            isFavourite = true,
            likeLevel = LikeLevel.SUPER,
        )
        val song = userSong.toSong()
        assertEquals(userSong.id, song.id)
        assertEquals(userSong.title, song.title)
        assertEquals(userSong.artists, song.artists)
        assertEquals(userSong.album, song.album)
        assertEquals(userSong.duration, song.duration)
        assertEquals(userSong.explicit, song.explicit)
        assertEquals(userSong.releaseDate, song.releaseDate)
        assertEquals(userSong.lyricsInfo, song.lyricsInfo)
        assertEquals(userSong.path, song.path)
        assertEquals(userSong.originalUrl, song.originalUrl)
        assertEquals(userSong.trackNumber, song.trackNumber)
        assertEquals(userSong.discNumber, song.discNumber)
        assertEquals(userSong.copyright, song.copyright)
        assertEquals(userSong.audio, song.audio)
        assertEquals(userSong.atmos, song.atmos)
        assertEquals(userSong.coverId, song.coverId)
        assertEquals(userSong.blurHash, song.blurHash)
        assertEquals(userSong.musicBrainzId, song.musicBrainzId)
        assertEquals(userSong.isrc, song.isrc)
        assertEquals(userSong.genres, song.genres)
        assertEquals(userSong.animatedCoverId, song.animatedCoverId)
        assertEquals(userSong.animatedCoverImageId, song.animatedCoverImageId)
        assertEquals(userSong.animatedCoverBlurHash, song.animatedCoverBlurHash)
        assertEquals(userSong.audioStartMs, song.audioStartMs)
        assertEquals(userSong.tags, song.tags)
    }
}
