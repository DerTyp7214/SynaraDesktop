package dev.dertyp.synara.screens

import dev.dertyp.PlatformUUID
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.ISongService
import dev.dertyp.services.models.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SongLyricsEditorTest {
    private val songId = PlatformUUID.randomUUID()
    private val candidateId = PlatformUUID.randomUUID()
    private val songService = mockk<ISongService>(relaxed = true)
    private val lyricsService = mockk<ILyricsCandidateService>()
    private val editor = SongLyricsEditor(songService, lyricsService)

    private fun candidate(
        providerId: String,
        level: LyricsSyncLevel,
        plain: String = "",
        lyrics: SyncedLyrics? = null,
        id: PlatformUUID = candidateId
    ) = mockk<LyricsCandidate> {
        every { this@mockk.id } returns id
        every { provider } returns LyricsProviderInfo(providerId, providerId, null, true, 0)
        every { syncLevel } returns level
        every { this@mockk.plain } returns plain
        every { this@mockk.lyrics } returns lyrics
    }

    private fun result(vararg candidates: LyricsCandidate) =
        LyricsSearchResult(candidates.toList(), null)

    @Test
    fun loadsLineSyncedOwnLyricsAsLrcLines() = runTest {
        val other = candidate("lrclib", LyricsSyncLevel.LINE, id = PlatformUUID.randomUUID())
        val own = candidate("song", LyricsSyncLevel.LINE)
        val synced = SyncedLyrics(
            listOf(
                LyricLine(
                    65_430.milliseconds, 70_000.milliseconds,
                    listOf(LyricWord("Hel", 65_430.milliseconds, 65_600.milliseconds), LyricWord("lo", 65_600.milliseconds, 66_000.milliseconds)),
                    text = "Hello"
                ),
                LyricLine(
                    70_000.milliseconds, 71_000.milliseconds,
                    listOf(LyricWord("a", 70_000.milliseconds, 70_500.milliseconds), LyricWord("b", 70_500.milliseconds, 71_000.milliseconds))
                )
            )
        )
        coEvery { lyricsService.getLyricsCandidates(songId, false) } returns result(other, own)
        coEvery { lyricsService.getLyricsCandidate(candidateId) } returns candidate("song", LyricsSyncLevel.LINE, lyrics = synced)

        assertEquals(listOf("[01:05.43] Hello", "[01:10.00] a b"), editor.load(songId))
    }

    @Test
    fun loadsPlainOwnLyricsAsNonBlankLines() = runTest {
        coEvery { lyricsService.getLyricsCandidates(songId, false) } returns result(candidate("song", LyricsSyncLevel.PLAIN))
        coEvery { lyricsService.getLyricsCandidate(candidateId) } returns candidate("song", LyricsSyncLevel.PLAIN, plain = "one\n\ntwo\n  \nthree")

        assertEquals(listOf("one", "two", "three"), editor.load(songId))
    }

    @Test
    fun loadsNothingWithoutOwnCandidate() = runTest {
        coEvery { lyricsService.getLyricsCandidates(songId, false) } returns result(candidate("lrclib", LyricsSyncLevel.LINE))

        assertEquals(emptyList(), editor.load(songId))
        coVerify(exactly = 0) { lyricsService.getLyricsCandidate(any()) }
    }

    @Test
    fun saveCallsSetLyricsOnlyWhenChanged() = runTest {
        assertFalse(editor.save(songId, listOf("a"), listOf("a")))
        coVerify(exactly = 0) { songService.setLyrics(any(), any()) }

        assertTrue(editor.save(songId, listOf("a"), listOf("a", "b")))
        coVerify(exactly = 1) { songService.setLyrics(songId, listOf("a", "b")) }
    }

    @Test
    fun importFormatFollowsExtension() {
        assertEquals(LyricsImportFormat.TTML, lyricsImportFormat("Song.TTML"))
        assertEquals(LyricsImportFormat.LRC, lyricsImportFormat("song.lrc"))
        assertEquals(LyricsImportFormat.LYRICSFILE, lyricsImportFormat("song.lyricsfile.yaml"))
        assertEquals(LyricsImportFormat.LYRICSFILE, lyricsImportFormat("song.yml"))
        assertNull(lyricsImportFormat("song.txt"))
    }

    @Test
    fun importCallsServiceWithFormatAndContent() = runTest {
        val expected = result(candidate("song", LyricsSyncLevel.WORD))
        coEvery { lyricsService.importSyncedLyrics(songId, any()) } returns expected

        assertEquals(expected, editor.import(songId, "a.ttml", "<tt/>"))
        coVerify { lyricsService.importSyncedLyrics(songId, LyricsImport(LyricsImportFormat.TTML, "<tt/>")) }

        assertNull(editor.import(songId, "a.txt", "x"))
        coVerify(exactly = 1) { lyricsService.importSyncedLyrics(any(), any()) }
    }
}
