package dev.dertyp.synara.player

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.SongTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.ISongService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AllSongsSourceTest {

    @Serializable
    private sealed class LegacySource {
        @Serializable
        @SerialName("dev.dertyp.synara.player.PlaybackSource.AllSongs")
        data class AllSongs(
            val tags: List<SongTag> = emptyList(),
            val invertTags: Boolean = false,
            val id: String = "all_songs"
        ) : LegacySource()
    }

    private val fullFilter = PlaybackSource.AllSongs(
        tags = listOf(SongTag.HAS_LYRICS),
        excludeTags = listOf(SongTag.B_16, SongTag.Q_44_48),
        titleTags = listOf(TitleTagKind.REMIX),
        excludeTitleTags = listOf(TitleTagKind.LIVE)
    )

    @Test
    fun idIsStableWithoutFilters() {
        assertEquals("all_songs", PlaybackSource.AllSongs().id)
    }

    @Test
    fun idIncludesEveryFilter() {
        assertEquals("all_songs_t:HAS_LYRICS_xt:B_16,Q_44_48_tt:REMIX_xtt:LIVE", fullFilter.id)
        val ids = listOf(
            PlaybackSource.AllSongs(tags = listOf(SongTag.B_24)),
            PlaybackSource.AllSongs(excludeTags = listOf(SongTag.B_24)),
            PlaybackSource.AllSongs(titleTags = listOf(TitleTagKind.LIVE)),
            PlaybackSource.AllSongs(excludeTitleTags = listOf(TitleTagKind.LIVE))
        ).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotEquals("all_songs", it) }
    }

    @Test
    fun queueSourcePassesTheFullFilter() = runTest {
        val songService = mockk<ISongService>()
        coEvery { songService.allSongs(any(), any(), any(), any(), any(), any(), any()) } returns
            PaginatedResponse(data = emptyList<UserSong>(), total = 3)
        every { songService.allSongIds(any(), any(), any(), any(), any()) } returns emptyFlow()

        val source = fullFilter.toQueueSource(songService)!!
        assertEquals(3, source.getSize())
        source.getIdFlow().toList()

        coVerify {
            songService.allSongs(0, 1, true, fullFilter.tags, fullFilter.excludeTags, fullFilter.titleTags, fullFilter.excludeTitleTags)
        }
        verify {
            songService.allSongIds(true, fullFilter.tags, fullFilter.excludeTags, fullFilter.titleTags, fullFilter.excludeTitleTags)
        }
    }

    @Test
    fun sourceRoundTripsThroughCbor() {
        val bytes = AppCbor.encodeToByteArray<PlaybackSource>(fullFilter)
        assertEquals(fullFilter, AppCbor.decodeFromByteArray<PlaybackSource>(bytes))
    }

    @Test
    fun storedInvertedQueueMapsTagsToExcludeTags() {
        val bytes = AppCbor.encodeToByteArray<LegacySource>(
            LegacySource.AllSongs(tags = listOf(SongTag.B_24, SongTag.Q_96), invertTags = true, id = "all_songs_B_24_Q_96_true")
        )
        assertEquals(
            PlaybackSource.AllSongs(excludeTags = listOf(SongTag.B_24, SongTag.Q_96)),
            AppCbor.decodeFromByteArray<PlaybackSource>(bytes)
        )
    }

    @Test
    fun storedPlainQueueKeepsTags() {
        val bytes = AppCbor.encodeToByteArray<LegacySource>(
            LegacySource.AllSongs(tags = listOf(SongTag.HAS_LYRICS), invertTags = false, id = "all_songs_HAS_LYRICS_false")
        )
        assertEquals(
            PlaybackSource.AllSongs(tags = listOf(SongTag.HAS_LYRICS)),
            AppCbor.decodeFromByteArray<PlaybackSource>(bytes)
        )
    }
}
