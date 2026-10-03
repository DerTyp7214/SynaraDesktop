@file:UseContextualSerialization(PlatformUUID::class)
@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RepeatMode
import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyPlayerStateTest {

    @Serializable
    private class OldArtist(
        val id: PlatformUUID,
        val name: String,
        val isGroup: Boolean,
        val artists: List<OldArtist> = emptyList(),
        val about: String = "",
        val musicbrainzId: PlatformUUID? = null,
    )

    @Serializable
    private class OldAlbum(
        val id: PlatformUUID,
        val name: String,
        val artists: List<OldArtist>,
        val releaseDate: String? = null,
        val totalDuration: Long,
        val musicbrainzId: PlatformUUID? = null,
    )

    @Serializable
    private class OldSong(
        val id: PlatformUUID,
        val title: String,
        val artists: List<OldArtist>,
        val album: OldAlbum?,
        val duration: Long,
        val explicit: Boolean,
        val path: String,
        val musicBrainzId: PlatformUUID? = null,
    )

    @Serializable
    private sealed class OldQueueEntry {
        @Serializable
        @SerialName("dev.dertyp.synara.player.QueueEntry.FromSource")
        class FromSource(val songId: PlatformUUID, val queueId: Long) : OldQueueEntry()

        @Serializable
        @SerialName("dev.dertyp.synara.player.QueueEntry.Explicit")
        class Explicit(val song: OldSong, val queueId: Long) : OldQueueEntry()
    }

    @Serializable
    private class OldPlayerState(
        val queue: List<OldQueueEntry>,
        val originalQueue: List<OldQueueEntry>,
        val currentIndex: Int,
        val repeatMode: RepeatMode,
        val shuffleMode: Boolean,
        val lastPosition: Long,
    )

    private val songMbid = randomPlatformUUID()
    private val albumMbid = randomPlatformUUID()
    private val artistMbid = randomPlatformUUID()
    private val memberMbid = randomPlatformUUID()
    private val albumArtistMbid = randomPlatformUUID()

    private val member = OldArtist(randomPlatformUUID(), "Member", false, musicbrainzId = memberMbid)
    private val group = OldArtist(randomPlatformUUID(), "Group", true, listOf(member), "Bio", artistMbid)
    private val albumArtist = OldArtist(randomPlatformUUID(), "Album Artist", false, musicbrainzId = albumArtistMbid)
    private val album = OldAlbum(randomPlatformUUID(), "Album", listOf(albumArtist), totalDuration = 1000, musicbrainzId = albumMbid)
    private val song = OldSong(randomPlatformUUID(), "Song", listOf(group), album, 1000, true, "/song.flac", songMbid)
    private val fromSourceId = randomPlatformUUID()

    private val oldState = OldPlayerState(
        queue = listOf(OldQueueEntry.Explicit(song, 1L), OldQueueEntry.FromSource(fromSourceId, 2L)),
        originalQueue = listOf(OldQueueEntry.FromSource(fromSourceId, 2L), OldQueueEntry.Explicit(song, 1L)),
        currentIndex = 1,
        repeatMode = RepeatMode.ALL,
        shuffleMode = true,
        lastPosition = 1234L,
    )

    private fun assertRestored(entry: QueueEntry) {
        val restored = assertIs<QueueEntry.Explicit>(entry).song
        assertEquals(songMbid, restored.musicBrainzId)
        assertTrue(restored.explicit)
        assertEquals(artistMbid, restored.artists.single().musicBrainzId)
        assertEquals(memberMbid, restored.artists.single().artists.single().musicBrainzId)
        assertEquals(albumMbid, restored.album?.musicBrainzId)
        assertEquals(albumArtistMbid, restored.album?.artists?.single()?.musicBrainzId)
    }

    @Test
    fun legacyMusicBrainzIdsSurvive() {
        val bytes = AppCbor.encodeToByteArray(oldState)
        val plain = AppCbor.decodeFromByteArray(PlayerState.serializer(), bytes)
        assertNull(assertIs<QueueEntry.Explicit>(plain.queue[0]).song.album?.musicBrainzId)

        val migrated = decodeLegacyPlayerState(AppCbor, bytes)

        assertRestored(migrated.queue[0])
        assertRestored(migrated.originalQueue[1])
        assertEquals(QueueEntry.FromSource(fromSourceId, 2L), migrated.queue[1])
        assertEquals(1, migrated.currentIndex)
        assertEquals(RepeatMode.ALL, migrated.repeatMode)
        assertTrue(migrated.shuffleMode)
    }

    @Test
    fun migratingTwiceIsANoOp() {
        val once = decodeLegacyPlayerState(AppCbor, AppCbor.encodeToByteArray(oldState))
        val bytes = AppCbor.encodeToByteArray(once)
        val twice = decodeLegacyPlayerState(AppCbor, bytes)
        assertEquals(once, twice)
        assertEquals(AppCbor.decodeFromByteArray(PlayerState.serializer(), bytes), twice)
    }

    @Test
    fun emptyStateDecodes() {
        val bytes = AppCbor.encodeToByteArray(PlayerState())
        assertEquals(PlayerState(), decodeLegacyPlayerState(AppCbor, bytes))
    }
}
