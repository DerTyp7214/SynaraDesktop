@file:UseContextualSerialization(PlatformUUID::class)
package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.random.Random

@Serializable
sealed class PlaybackSource {
    abstract val id: String
    @Serializable data object AllSongs : PlaybackSource() { override val id = "all_songs" }
    @Serializable data object LikedSongs : PlaybackSource() { override val id = "liked_songs" }
    @Serializable data class Album(val albumId: PlatformUUID, val name: String) : PlaybackSource() { override val id = "album_$albumId" }
    @Serializable data class Artist(val artistId: PlatformUUID, val name: String) : PlaybackSource() { override val id = "artist_$artistId" }
    @Serializable data class Playlist(val playlistId: PlatformUUID, val name: String, val isUserPlaylist: Boolean = false) : PlaybackSource() { override val id = "playlist_$playlistId" }
    @Serializable data class Search(val query: String) : PlaybackSource() { override val id = "search_$query" }
    @Serializable data object Manual : PlaybackSource() { override val id = "manual" }
}

fun PlaybackSource.toQueueSource(songService: ISongService): QueueSource? = when (this) {
    is PlaybackSource.AllSongs -> AllSongsQueueSource(songService)
    is PlaybackSource.LikedSongs -> LikedSongsQueueSource(songService)
    is PlaybackSource.Album -> AlbumQueueSource(songService, albumId)
    is PlaybackSource.Artist -> ArtistQueueSource(songService, artistId)
    is PlaybackSource.Playlist -> PlaylistQueueSource(songService, playlistId, isUserPlaylist)
    is PlaybackSource.Search -> SearchQueueSource(songService, query)
    is PlaybackSource.Manual -> null
}

@Serializable
sealed class QueueEntry {
    abstract val queueId: Long
    abstract val songId: PlatformUUID

    @Serializable
    data class FromSource(
        override val songId: PlatformUUID,
        override val queueId: Long = Random.nextLong()
    ) : QueueEntry()

    @Serializable
    data class Explicit(
        val song: UserSong,
        override val queueId: Long = Random.nextLong()
    ) : QueueEntry() {
        override val songId: PlatformUUID = song.id
    }
}

@Serializable
data class PlaybackQueue(
    val source: PlaybackSource = PlaybackSource.Manual,
    val items: List<QueueEntry> = emptyList()
)
