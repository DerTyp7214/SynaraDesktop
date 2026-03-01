@file:UseContextualSerialization(PlatformUUID::class)
package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.random.Random

@Serializable
data class PlaybackQueue(
    val items: List<QueueEntry> = emptyList(),
    val source: PlaybackSource = PlaybackSource.Manual
)


@Serializable
sealed class PlaybackSource {
    abstract val id: String

    @Serializable
    data object Manual : PlaybackSource() {
        override val id: String = "manual"
    }

    @Serializable
    data class Playlist(val playlistId: PlatformUUID) : PlaybackSource() {
        override val id: String = "playlist_$playlistId"
    }

    @Serializable
    data class Album(val albumId: PlatformUUID) : PlaybackSource() {
        override val id: String = "album_$albumId"
    }

    @Serializable
    data class Artist(val artistId: PlatformUUID) : PlaybackSource() {
        override val id: String = "artist_$artistId"
    }

    @Serializable
    data object AllSongs : PlaybackSource() {
        override val id: String = "all_songs"
    }

    @Serializable
    data object LikedSongs : PlaybackSource() {
        override val id: String = "liked_songs"
    }
}

fun PlaybackSource.toQueueSource(songService: ISongService): QueueSource? {
    return when (this) {
        PlaybackSource.AllSongs -> AllSongsQueueSource(songService)
        PlaybackSource.LikedSongs -> LikedSongsQueueSource(songService)
        is PlaybackSource.Album -> AlbumQueueSource(songService, albumId)
        is PlaybackSource.Artist -> ArtistQueueSource(songService, artistId)
        is PlaybackSource.Playlist -> PlaylistQueueSource(songService, playlistId, true)
        PlaybackSource.Manual -> null
    }
}

@Serializable
sealed class QueueEntry {
    abstract val queueId: Long

    @Serializable
    data class FromSource(
        val songId: PlatformUUID,
        override val queueId: Long = Random.nextLong()
    ) : QueueEntry()

    @Serializable
    data class Explicit(
        val song: UserSong,
        override val queueId: Long = Random.nextLong()
    ) : QueueEntry()
}
