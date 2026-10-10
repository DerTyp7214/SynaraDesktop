package dev.dertyp.synara.testing

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.QueueEntry

fun testArtist(index: Int) = ArtistCredit(randomPlatformUUID(), "Artist $index", false)

fun testAlbum(index: Int) = Album(
    id = randomPlatformUUID(),
    name = "Album $index",
    artists = emptyList(),
    releaseDate = null,
    totalDuration = 0
)

fun testSong(
    index: Int,
    id: PlatformUUID = randomPlatformUUID(),
    duration: Long = 240_000,
    album: Album? = null,
    artists: List<ArtistCredit> = emptyList()
) = UserSong(
    id = id,
    title = "Song $index",
    artists = artists,
    album = album,
    duration = duration,
    explicit = false,
    path = "/song$index.flac"
)

fun testSongs(count: Int) = List(count) { testSong(it) }

fun queueOf(songs: List<UserSong>) = PlaybackQueue(items = songs.map { QueueEntry.Explicit(it) })
