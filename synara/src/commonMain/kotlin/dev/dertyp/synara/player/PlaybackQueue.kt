@file:UseContextualSerialization(PlatformUUID::class)
package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

    @Serializable(with = AllSongsSerializer::class)
    data class AllSongs(
        val tags: List<SongTag> = emptyList(),
        val excludeTags: List<SongTag> = emptyList(),
        val titleTags: List<TitleTagKind> = emptyList(),
        val excludeTitleTags: List<TitleTagKind> = emptyList()
    ) : PlaybackSource() {
        override val id: String = buildString {
            append("all_songs")
            appendFilter("t", tags)
            appendFilter("xt", excludeTags)
            appendFilter("tt", titleTags)
            appendFilter("xtt", excludeTitleTags)
        }
    }

    @Serializable
    data object LikedSongs : PlaybackSource() {
        override val id: String = "liked_songs"
    }

    @Serializable
    data object SuperLikedSongs : PlaybackSource() {
        override val id: String = "super_liked_songs"
    }

    @Serializable
    data class Radio(
        val sessionId: PlatformUUID,
        val name: String? = null
    ) : PlaybackSource() {
        override val id: String = "radio_$sessionId"
    }
}

private fun StringBuilder.appendFilter(key: String, values: List<Enum<*>>) {
    if (values.isEmpty()) return
    append('_').append(key).append(':').append(values.joinToString(",") { it.name })
}

@Serializable
@SerialName("dev.dertyp.synara.player.PlaybackSource.AllSongs")
private class AllSongsSurrogate(
    val tags: List<SongTag> = emptyList(),
    val invertTags: Boolean = false,
    val excludeTags: List<SongTag> = emptyList(),
    val titleTags: List<TitleTagKind> = emptyList(),
    val excludeTitleTags: List<TitleTagKind> = emptyList()
)

internal object AllSongsSerializer : KSerializer<PlaybackSource.AllSongs> {
    override val descriptor: SerialDescriptor = AllSongsSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PlaybackSource.AllSongs) {
        encoder.encodeSerializableValue(
            AllSongsSurrogate.serializer(),
            AllSongsSurrogate(
                tags = value.tags,
                excludeTags = value.excludeTags,
                titleTags = value.titleTags,
                excludeTitleTags = value.excludeTitleTags
            )
        )
    }

    override fun deserialize(decoder: Decoder): PlaybackSource.AllSongs {
        val surrogate = decoder.decodeSerializableValue(AllSongsSurrogate.serializer())
        return if (surrogate.invertTags) {
            PlaybackSource.AllSongs(
                excludeTags = (surrogate.excludeTags + surrogate.tags).distinct(),
                titleTags = surrogate.titleTags,
                excludeTitleTags = surrogate.excludeTitleTags
            )
        } else {
            PlaybackSource.AllSongs(
                tags = surrogate.tags,
                excludeTags = surrogate.excludeTags,
                titleTags = surrogate.titleTags,
                excludeTitleTags = surrogate.excludeTitleTags
            )
        }
    }
}

val PlaybackSource.isEndless: Boolean
    get() = this is PlaybackSource.Radio

fun PlaybackSource.toQueueSource(songService: ISongService): QueueSource? {
    return when (this) {
        is PlaybackSource.AllSongs -> AllSongsQueueSource(
            songService,
            tags = tags,
            excludeTags = excludeTags,
            titleTags = titleTags,
            excludeTitleTags = excludeTitleTags
        )
        PlaybackSource.LikedSongs -> LikedSongsQueueSource(songService)
        PlaybackSource.SuperLikedSongs -> SuperLikedSongsQueueSource(songService)
        is PlaybackSource.Album -> AlbumQueueSource(songService, albumId)
        is PlaybackSource.Artist -> ArtistQueueSource(songService, artistId)
        is PlaybackSource.Playlist -> PlaylistQueueSource(songService, playlistId, true)
        is PlaybackSource.Radio -> null
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
