package dev.dertyp.synara.ui.components.lyrics

import dev.dertyp.services.models.*
import dev.dertyp.synara.podcast.TranscriptCue
import kotlin.math.abs

data class DisplayWord(
    val start: Long,
    val end: Long,
    val startIndex: Int,
    val endIndex: Int
)

data class DisplayLine(
    val start: Long,
    val end: Long,
    val text: String,
    val words: List<DisplayWord>? = null,
    val background: Boolean = false,
    val voiceIndex: Int? = null,
    val group: Int = 0,
    val label: String? = null,
    val backing: List<DisplayLine> = emptyList()
)

data class LyricsDisplay(val lines: List<DisplayLine>) {
    val isEmpty: Boolean get() = lines.isEmpty()

    val wordTimed: Boolean
        get() = lines.any { line -> line.words != null || line.backing.any { it.words != null } }

    companion object {
        const val PLAIN_SPACING_MS = 2000L

        val EMPTY = LyricsDisplay(emptyList())

        private val timeRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]""")

        fun from(candidate: LyricsCandidate): LyricsDisplay {
            val synced = candidate.lyrics
            return if (candidate.syncLevel == LyricsSyncLevel.PLAIN || synced == null || synced.lines.isEmpty()) {
                fromText(candidate.plain)
            } else {
                fromSynced(synced, candidate.syncLevel == LyricsSyncLevel.WORD)
            }
        }

        fun fromSynced(lyrics: SyncedLyrics, wordTimed: Boolean): LyricsDisplay {
            val personVoices = lyrics.lines
                .mapNotNull { it.voice }
                .distinct()
                .filter { lyrics.voices[it]?.type != LyricVoiceType.GROUP }
            val duet = personVoices.size > 1
            val converted = lyrics.lines
                .map { line ->
                    val voiceIndex = if (duet) personVoices.indexOf(line.voice).takeIf { it >= 0 } else null
                    convert(line, wordTimed, voiceIndex)
                }
                .filter { it.text.isNotBlank() }
                .sortedBy { it.start }

            val leads = converted.filter { !it.background }.toMutableList()
            val orphans = mutableListOf<DisplayLine>()
            converted.filter { it.background }.forEach { backing ->
                val leadIndex = leads.indices
                    .filter { leads[it].group == backing.group }
                    .minByOrNull { abs(leads[it].start - backing.start) }
                if (leadIndex == null) {
                    orphans += backing
                } else {
                    val lead = leads[leadIndex]
                    leads[leadIndex] = lead.copy(backing = lead.backing + backing.copy(voiceIndex = backing.voiceIndex ?: lead.voiceIndex))
                }
            }
            return LyricsDisplay((leads + orphans).sortedBy { it.start })
        }

        fun fromText(text: String): LyricsDisplay {
            if (text.isBlank()) return EMPTY
            val parsed = mutableListOf<Pair<Long, String>>()
            text.lines().forEach { line ->
                val match = timeRegex.find(line)
                if (match != null) {
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val msStr = match.groupValues[3]
                    val ms = msStr.toLong() * (if (msStr.length == 2) 10 else 1)
                    val content = line.substring(match.range.last + 1).trim()
                    if (content.isNotEmpty()) parsed += (min * 60 * 1000 + sec * 1000 + ms) to content
                } else if (line.isNotBlank() && !line.startsWith("[")) {
                    parsed += 0L to line.trim()
                }
            }
            val timed = if (parsed.all { it.first == 0L }) {
                parsed.mapIndexed { index, (_, content) -> index * PLAIN_SPACING_MS to content }
            } else {
                parsed.sortedBy { it.first }
            }
            return LyricsDisplay(timed.mapIndexed { index, (start, content) ->
                DisplayLine(start = start, end = timed.getOrNull(index + 1)?.first ?: (start + PLAIN_SPACING_MS), text = content)
            })
        }

        fun fromTranscript(cues: List<TranscriptCue>): LyricsDisplay =
            LyricsDisplay(cues.map { DisplayLine(start = it.startMs, end = it.endMs, text = it.text, label = it.speaker) })

        private fun convert(line: LyricLine, wordTimed: Boolean, voiceIndex: Int?): DisplayLine {
            val text = line.text ?: line.words.joinToString(" ") { it.text }
            val separator = if (line.text != null) 0 else 1
            val words = if (wordTimed && line.words.isNotEmpty()) {
                var offset = 0
                line.words.mapIndexed { index, word ->
                    if (index > 0) offset += separator
                    val startIndex = offset.coerceAtMost(text.length)
                    offset += word.text.length
                    DisplayWord(
                        start = word.startTime.inWholeMilliseconds,
                        end = word.endTime.inWholeMilliseconds,
                        startIndex = startIndex,
                        endIndex = offset.coerceAtMost(text.length)
                    )
                }
            } else null
            return DisplayLine(
                start = line.startTime.inWholeMilliseconds,
                end = line.endTime.inWholeMilliseconds,
                text = text,
                words = words,
                background = line.background,
                voiceIndex = voiceIndex,
                group = line.group
            )
        }
    }
}
