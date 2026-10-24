package dev.dertyp.synara.db

import dev.dertyp.data.UserSong
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.models.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class ExposedOfflineLyricsStoreTest {
    private lateinit var file: Path
    private lateinit var database: Database
    private val store = ExposedOfflineLyricsStore()
    private val library = ExposedLibraryRepository()

    private val song = UserSong(
        id = randomPlatformUUID(),
        title = "Dang!",
        artists = emptyList(),
        album = null,
        duration = 284_000,
        explicit = false,
        path = "/Mac Miller/The Divine Feminine/dang.flac"
    )

    private val wordLyrics = SyncedLyrics(
        lines = listOf(
            LyricLine(
                startTime = 1_000.milliseconds,
                endTime = 2_400.milliseconds,
                words = listOf(
                    LyricWord("Hel", 1_000.milliseconds, 1_200.milliseconds, listOf(LyricChar("H", 1_000.milliseconds, 1_050.milliseconds))),
                    LyricWord("lo ", 1_200.milliseconds, 1_500.milliseconds),
                    LyricWord("there", 1_500.milliseconds, 2_400.milliseconds)
                ),
                text = "Hello there",
                voice = "v1",
                group = 1
            ),
            LyricLine(
                startTime = 1_800.milliseconds,
                endTime = 2_400.milliseconds,
                words = listOf(LyricWord("(ooh)", 1_800.milliseconds, 2_400.milliseconds)),
                text = "(ooh)",
                voice = "v2",
                background = true,
                group = 1
            )
        ),
        voices = mapOf("v1" to LyricVoice("Mac Miller"), "v2" to LyricVoice("Choir", LyricVoiceType.GROUP))
    )

    private fun candidate(
        syncLevel: LyricsSyncLevel = LyricsSyncLevel.WORD,
        lyrics: SyncedLyrics? = wordLyrics,
        plain: String = "Hello there"
    ) = LyricsCandidate(
        id = randomPlatformUUID(),
        songId = song.id,
        provider = LyricsProviderInfo("betterlyrics", "Better Lyrics", "https://example.org", enabled = true, priority = 2),
        syncLevel = syncLevel,
        instrumental = false,
        match = LyricsMatch.VERIFIED,
        durationGapSec = -1,
        rank = 1,
        preferred = true,
        fetchedAt = Date(1_700_000_000_123L),
        plain = plain,
        lyrics = lyrics
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-offline-lyrics", ".db")
        database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(
                DownloadedImages,
                DownloadedSongs,
                DownloadedAlbums,
                DownloadedArtists,
                DownloadedGenres,
                DownloadedSongArtists,
                DownloadedAlbumArtists,
                DownloadedArtistMembers,
                DownloadedUserPlaylists,
                DownloadedUserPlaylistSongs,
                DownloadedSongGenres,
                DownloadedAlbumGenres,
                DownloadedArtistGenres,
                DownloadedSongLyrics
            )
        }
        runBlocking { library.saveSongMetadata(song, explicitlySaved = true) }
    }

    @AfterTest
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        file.deleteIfExists()
    }

    private fun storedRows(): Int = transaction(database) { DownloadedSongLyrics.selectAll().count().toInt() }

    @Test
    fun wordSyncedLyricsComeBackUnchanged() = runBlocking {
        val stored = candidate()

        store.saveLyrics(song.id, stored)

        assertEquals(stored, store.getLyrics(song.id))
    }

    @Test
    fun plainLyricsWithoutContentComeBackUnchanged() = runBlocking {
        val stored = candidate(syncLevel = LyricsSyncLevel.PLAIN, lyrics = null, plain = "first line\nsecond line")
            .copy(provider = LyricsProviderInfo("song", "Song", null, enabled = false, priority = 7), durationGapSec = null, preferred = false)

        store.saveLyrics(song.id, stored)

        assertEquals(stored, store.getLyrics(song.id))
    }

    @Test
    fun savingAgainReplacesTheStoredCandidate() = runBlocking {
        store.saveLyrics(song.id, candidate())
        val replacement = candidate(syncLevel = LyricsSyncLevel.PLAIN, lyrics = null, plain = "new text")

        store.saveLyrics(song.id, replacement)

        assertEquals(replacement, store.getLyrics(song.id))
        assertEquals(1, storedRows())
    }

    @Test
    fun aSongWithoutStoredLyricsHasNone() = runBlocking {
        assertNull(store.getLyrics(song.id))
    }

    @Test
    fun deletedLyricsAreGone() = runBlocking {
        store.saveLyrics(song.id, candidate())

        store.deleteLyrics(song.id)

        assertNull(store.getLyrics(song.id))
    }

    @Test
    fun deletingTheSongDeletesItsLyrics() = runBlocking {
        store.saveLyrics(song.id, candidate())

        library.deleteSong(song.id)

        assertNull(store.getLyrics(song.id))
        assertEquals(0, storedRows())
    }
}
