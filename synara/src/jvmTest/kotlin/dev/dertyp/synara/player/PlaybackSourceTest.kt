package dev.dertyp.synara.player

import dev.dertyp.data.RepeatMode
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.ISongService
import dev.dertyp.synara.testing.testAlbum
import dev.dertyp.synara.testing.testArtist
import dev.dertyp.synara.testing.testSong
import io.mockk.mockk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class PlaybackSourceTest {
    private val songService = mockk<ISongService>()

    private fun everySource(): List<PlaybackSource> = listOf(
        PlaybackSource.Manual,
        PlaybackSource.Playlist(randomPlatformUUID()),
        PlaybackSource.Album(randomPlatformUUID()),
        PlaybackSource.Artist(randomPlatformUUID()),
        PlaybackSource.AllSongs(),
        PlaybackSource.LikedSongs,
        PlaybackSource.SuperLikedSongs,
        PlaybackSource.Radio(randomPlatformUUID())
    )

    @Test
    fun everyVariantHasItsOwnId() {
        val playlistId = randomPlatformUUID()
        val albumId = randomPlatformUUID()
        val artistId = randomPlatformUUID()
        val sessionId = randomPlatformUUID()

        assertEquals("manual", PlaybackSource.Manual.id)
        assertEquals("playlist_$playlistId", PlaybackSource.Playlist(playlistId).id)
        assertEquals("album_$albumId", PlaybackSource.Album(albumId).id)
        assertEquals("artist_$artistId", PlaybackSource.Artist(artistId).id)
        assertEquals("all_songs", PlaybackSource.AllSongs().id)
        assertEquals("liked_songs", PlaybackSource.LikedSongs.id)
        assertEquals("super_liked_songs", PlaybackSource.SuperLikedSongs.id)
        assertEquals("radio_$sessionId", PlaybackSource.Radio(sessionId).id)
        assertEquals("radio_$sessionId", PlaybackSource.Radio(sessionId, name = "Station").id)

        val ids = everySource().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun onlyRadioIsEndless() {
        everySource().forEach { source ->
            assertEquals(source is PlaybackSource.Radio, source.isEndless)
        }
        assertTrue(PlaybackSource.Radio(randomPlatformUUID()).isEndless)
        assertFalse(PlaybackSource.Manual.isEndless)
    }

    @Test
    fun manualAndRadioHaveNoQueueSource() {
        assertNull(PlaybackSource.Manual.toQueueSource(songService))
        assertNull(PlaybackSource.Radio(randomPlatformUUID()).toQueueSource(songService))
    }

    @Test
    fun pagedSourcesMapToTheQueueSourceOfTheirTypeAndId() {
        val playlist = PlaybackSource.Playlist(randomPlatformUUID())
        val album = PlaybackSource.Album(randomPlatformUUID())
        val artist = PlaybackSource.Artist(randomPlatformUUID())
        val allSongs = PlaybackSource.AllSongs()

        assertEquals(playlist.id, assertIs<PlaylistQueueSource>(playlist.toQueueSource(songService)).id)
        assertEquals(album.id, assertIs<AlbumQueueSource>(album.toQueueSource(songService)).id)
        assertEquals(artist.id, assertIs<ArtistQueueSource>(artist.toQueueSource(songService)).id)
        assertEquals(allSongs.id, assertIs<AllSongsQueueSource>(allSongs.toQueueSource(songService)).id)
        assertEquals(
            PlaybackSource.LikedSongs.id,
            assertIs<LikedSongsQueueSource>(PlaybackSource.LikedSongs.toQueueSource(songService)).id
        )
        assertEquals(
            PlaybackSource.SuperLikedSongs.id,
            assertIs<SuperLikedSongsQueueSource>(PlaybackSource.SuperLikedSongs.toQueueSource(songService)).id
        )
    }

    @Test
    fun twoDefaultEntriesHaveDistinctQueueIds() {
        val song = testSong(1)

        assertNotEquals(QueueEntry.Explicit(song).queueId, QueueEntry.Explicit(song).queueId)
        assertNotEquals(QueueEntry.FromSource(song.id).queueId, QueueEntry.FromSource(song.id).queueId)
        assertNotEquals(QueueEntry.Explicit(song), QueueEntry.Explicit(song))
    }

    @Test
    fun playerStateWithMixedEntriesRoundTripsThroughCbor() {
        val explicit = QueueEntry.Explicit(testSong(1, album = testAlbum(1), artists = listOf(testArtist(1))))
        val fromSource = QueueEntry.FromSource(randomPlatformUUID())
        val userAdded = QueueEntry.Explicit(testSong(2))
        val state = PlayerState(
            queue = listOf(fromSource, explicit, userAdded),
            originalQueue = listOf(explicit, fromSource, userAdded),
            source = PlaybackSource.Playlist(randomPlatformUUID()),
            currentIndex = 1,
            repeatMode = RepeatMode.ALL,
            shuffleMode = true,
            lastPosition = 12_345
        )

        val decoded = AppCbor.decodeFromByteArray<PlayerState>(AppCbor.encodeToByteArray(state))

        assertEquals(state, decoded)
        assertEquals(listOf(fromSource.queueId, explicit.queueId, userAdded.queueId), decoded.queue.map { it.queueId })
        assertIs<QueueEntry.FromSource>(decoded.queue[0])
        assertIs<QueueEntry.Explicit>(decoded.queue[1])
    }
}
