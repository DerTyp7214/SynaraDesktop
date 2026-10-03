@file:UseContextualSerialization(PlatformUUID::class)
@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.ArtistCredit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray

@Serializable
private class LegacyPlayerState(
    val queue: List<LegacyQueueEntry> = emptyList(),
    val originalQueue: List<LegacyQueueEntry> = emptyList(),
)

@Serializable
private sealed class LegacyQueueEntry {
    @Serializable
    @SerialName("dev.dertyp.synara.player.QueueEntry.FromSource")
    class FromSource : LegacyQueueEntry()

    @Serializable
    @SerialName("dev.dertyp.synara.player.QueueEntry.Explicit")
    class Explicit(val song: LegacySong) : LegacyQueueEntry()
}

@Serializable
private class LegacySong(
    val artists: List<LegacyArtist> = emptyList(),
    val album: LegacyAlbum? = null,
)

@Serializable
private class LegacyAlbum(
    val id: PlatformUUID,
    val artists: List<LegacyArtist> = emptyList(),
    val musicbrainzId: PlatformUUID? = null,
)

@Serializable
private class LegacyArtist(
    val id: PlatformUUID,
    val artists: List<LegacyArtist> = emptyList(),
    val musicbrainzId: PlatformUUID? = null,
)

private class LegacyMusicBrainzIds(
    val albums: Map<PlatformUUID, PlatformUUID>,
    val artists: Map<PlatformUUID, PlatformUUID>,
)

private fun LegacyArtist.collectInto(target: MutableMap<PlatformUUID, PlatformUUID>) {
    musicbrainzId?.let { target[id] = it }
    artists.forEach { it.collectInto(target) }
}

private fun LegacyPlayerState.musicBrainzIds(): LegacyMusicBrainzIds {
    val albums = mutableMapOf<PlatformUUID, PlatformUUID>()
    val artists = mutableMapOf<PlatformUUID, PlatformUUID>()
    (queue + originalQueue).filterIsInstance<LegacyQueueEntry.Explicit>().forEach { entry ->
        entry.song.artists.forEach { it.collectInto(artists) }
        entry.song.album?.let { album ->
            album.musicbrainzId?.let { albums[album.id] = it }
            album.artists.forEach { it.collectInto(artists) }
        }
    }
    return LegacyMusicBrainzIds(albums, artists)
}

private fun ArtistCredit.restore(ids: LegacyMusicBrainzIds): ArtistCredit = copy(
    musicBrainzId = musicBrainzId ?: ids.artists[id],
    artists = artists.map { it.restore(ids) },
)

private fun Album.restore(ids: LegacyMusicBrainzIds): Album = copy(
    musicBrainzId = musicBrainzId ?: ids.albums[id],
    artists = artists.map { it.restore(ids) },
)

private fun QueueEntry.restore(ids: LegacyMusicBrainzIds): QueueEntry = when (this) {
    is QueueEntry.Explicit -> copy(
        song = song.copy(
            artists = song.artists.map { it.restore(ids) },
            album = song.album?.restore(ids),
        )
    )
    is QueueEntry.FromSource -> this
}

internal fun decodeLegacyPlayerState(cbor: Cbor, bytes: ByteArray): PlayerState {
    val state = cbor.decodeFromByteArray<PlayerState>(bytes)
    val ids = cbor.decodeFromByteArray<LegacyPlayerState>(bytes).musicBrainzIds()
    return state.copy(
        queue = state.queue.map { it.restore(ids) },
        originalQueue = state.originalQueue.map { it.restore(ids) },
    )
}
