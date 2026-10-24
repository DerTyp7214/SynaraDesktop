package dev.dertyp.synara.screens

import dev.dertyp.PlatformUUID
import dev.dertyp.services.ILyricsCandidateService
import dev.dertyp.services.ISongService
import dev.dertyp.services.models.*

const val SONG_LYRICS_PROVIDER_ID = "song"

val lyricsImportExtensions = setOf("ttml", "lrc", "yaml", "yml")

fun lyricsImportFormat(fileName: String): LyricsImportFormat? {
    val name = fileName.lowercase()
    return when {
        name.endsWith(".ttml") -> LyricsImportFormat.TTML
        name.endsWith(".lrc") -> LyricsImportFormat.LRC
        name.endsWith(".yaml") || name.endsWith(".yml") -> LyricsImportFormat.LYRICSFILE
        else -> null
    }
}

fun lrcLine(startMs: Long, text: String): String {
    val minutes = startMs / 60_000
    val seconds = (startMs / 1000) % 60
    val hundredths = (startMs / 10) % 100
    return "[%02d:%02d.%02d] %s".format(minutes, seconds, hundredths, text)
}

fun LyricsCandidate.toEditorLines(): List<String> {
    val synced = lyrics
    if (syncLevel != LyricsSyncLevel.PLAIN && synced != null) {
        return synced.lines.map { line ->
            lrcLine(line.startTime.inWholeMilliseconds, line.text ?: line.words.joinToString(" ") { it.text })
        }
    }
    return plain.lines().filter { it.isNotBlank() }
}

class SongLyricsEditor(
    private val songService: ISongService,
    private val lyricsService: ILyricsCandidateService
) {
    suspend fun load(songId: PlatformUUID): List<String> {
        val own = lyricsService.getLyricsCandidates(songId, includeContent = false)
            .candidates.firstOrNull { it.provider.id == SONG_LYRICS_PROVIDER_ID }
            ?: return emptyList()
        val full = lyricsService.getLyricsCandidate(own.id) ?: return emptyList()
        return full.toEditorLines()
    }

    suspend fun save(songId: PlatformUUID, loaded: List<String>, edited: List<String>): Boolean {
        if (loaded == edited) return false
        songService.setLyrics(songId, edited)
        return true
    }

    suspend fun import(songId: PlatformUUID, fileName: String, content: String): LyricsSearchResult? {
        val format = lyricsImportFormat(fileName) ?: return null
        return lyricsService.importSyncedLyrics(songId, LyricsImport(format, content))
    }
}
