package dev.dertyp.synara.podcast

import dev.dertyp.data.PodcastTranscript
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val speaker: String?,
    val text: String
)

data class ParsedTranscript(
    val cues: List<TranscriptCue>,
    val timed: Boolean
) {
    val isEmpty: Boolean get() = cues.isEmpty()
}

enum class TranscriptFormat { JSON, VTT, SRT, HTML, PLAIN }

object PodcastTranscriptParser {
    private const val MERGE_GAP_MS = 1_000L
    private const val MERGE_MAX_CHARS = 200

    private val timingRegex = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")
    private val voiceRegex = Regex("""<v(?:\.[^\s>]*)?\s+([^>]+)>""")
    private val blankLineRegex = Regex("""\n\s*\n""")
    private val whitespaceRegex = Regex("""\s+""")
    private val sentenceEndRegex = Regex("""[.!?…]["'”’)\]]*$""")
    private val attachedPunctuation = setOf(',', '.', '!', '?', ';', ':', '…', ')', ']', '”', '’', '%')

    fun formatOf(type: String): TranscriptFormat {
        val value = type.lowercase()
        return when {
            "json" in value -> TranscriptFormat.JSON
            "vtt" in value -> TranscriptFormat.VTT
            "srt" in value || "subrip" in value -> TranscriptFormat.SRT
            "html" in value -> TranscriptFormat.HTML
            else -> TranscriptFormat.PLAIN
        }
    }

    fun rank(format: TranscriptFormat): Int = when (format) {
        TranscriptFormat.JSON -> 0
        TranscriptFormat.VTT -> 1
        TranscriptFormat.SRT -> 2
        TranscriptFormat.HTML -> 3
        TranscriptFormat.PLAIN -> 4
    }

    fun ranked(transcripts: List<PodcastTranscript>): List<PodcastTranscript> =
        transcripts.sortedWith(compareBy<PodcastTranscript> { rank(formatOf(it.type)) }.thenBy { if (it.available) 0 else 1 })

    fun languages(transcripts: List<PodcastTranscript>): List<String?> =
        ranked(transcripts).map { it.language?.takeIf { l -> l.isNotBlank() } }.distinct()

    fun candidates(transcripts: List<PodcastTranscript>, language: String?): List<PodcastTranscript> {
        val all = ranked(transcripts)
        val matching = all.filter { (it.language?.takeIf { l -> l.isNotBlank() }) == language }
        return matching.ifEmpty { all }
    }

    fun preferredLanguage(transcripts: List<PodcastTranscript>, systemLanguage: String?): String? {
        val languages = languages(transcripts)
        if (systemLanguage != null) {
            languages.firstOrNull { it != null && it.substringBefore('-').equals(systemLanguage, ignoreCase = true) }
                ?.let { return it }
        }
        return languages.firstOrNull()
    }

    fun parse(content: String, type: String): ParsedTranscript = parse(content, detectFormat(content, formatOf(type)))

    fun parse(content: String, format: TranscriptFormat): ParsedTranscript {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
        val parsed = when (format) {
            TranscriptFormat.JSON -> parseJson(normalized)
            TranscriptFormat.VTT -> parseVtt(normalized)
            TranscriptFormat.SRT -> parseSrt(normalized)
            TranscriptFormat.HTML -> parseHtml(normalized)
            TranscriptFormat.PLAIN -> parsePlain(normalized)
        }
        if (parsed.isEmpty && format != TranscriptFormat.PLAIN && format != TranscriptFormat.HTML) {
            return if (normalized.contains('<')) parseHtml(normalized) else parsePlain(normalized)
        }
        return parsed
    }

    private fun detectFormat(content: String, declared: TranscriptFormat): TranscriptFormat {
        val head = content.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("WEBVTT") -> TranscriptFormat.VTT
            declared != TranscriptFormat.PLAIN -> declared
            head.startsWith("{") && "\"segments\"" in head -> TranscriptFormat.JSON
            head.lineSequence().take(4).any { "," in it && timingRegex.containsMatchIn(it) } -> TranscriptFormat.SRT
            else -> declared
        }
    }

    fun parseVtt(content: String): ParsedTranscript {
        val cues = mutableListOf<TranscriptCue>()
        for (block in blocks(content)) {
            val lines = block.lines()
            val first = lines.first().trim()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first == "STYLE" || first == "REGION") continue
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val cue = cueFromBlock(lines, timingIndex) ?: continue
            cues += cue
        }
        return ParsedTranscript(cues.sortedBy { it.startMs }, timed = cues.isNotEmpty())
    }

    fun parseSrt(content: String): ParsedTranscript {
        val cues = mutableListOf<TranscriptCue>()
        for (block in blocks(content)) {
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val cue = cueFromBlock(lines, timingIndex) ?: continue
            cues += cue
        }
        return ParsedTranscript(cues.sortedBy { it.startMs }, timed = cues.isNotEmpty())
    }

    private fun cueFromBlock(lines: List<String>, timingIndex: Int): TranscriptCue? {
        val match = timingRegex.find(lines[timingIndex]) ?: return null
        val start = parseTimestamp(match.groupValues[1]) ?: return null
        val end = parseTimestamp(match.groupValues[2]) ?: start
        val raw = lines.drop(timingIndex + 1).filter { it.isNotBlank() }.joinToString(" ")
        val speaker = voiceRegex.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val text = cleanText(raw)
        if (text.isEmpty()) return null
        return TranscriptCue(start, end.coerceAtLeast(start), speaker, text)
    }

    fun parseJson(content: String): ParsedTranscript {
        val root = runCatching { Json.parseToJsonElement(content) }.getOrNull() ?: return ParsedTranscript(emptyList(), false)
        val segments = when (root) {
            is JsonObject -> root["segments"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return ParsedTranscript(emptyList(), false)

        val raw = segments.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val start = obj.seconds("startTime") ?: return@mapNotNull null
            val end = obj.seconds("endTime") ?: start
            val body = obj.string("body")?.let(::cleanText).orEmpty()
            if (body.isEmpty()) return@mapNotNull null
            TranscriptCue(start, end.coerceAtLeast(start), obj.string("speaker")?.trim()?.takeIf { it.isNotEmpty() }, body)
        }.sortedBy { it.startMs }

        if (raw.isEmpty()) return ParsedTranscript(emptyList(), false)

        val wordLevel = raw.count { it.text.split(whitespaceRegex).size <= 2 } * 2 > raw.size
        val cues = if (wordLevel) mergeWords(raw) else raw
        return ParsedTranscript(cues, timed = true)
    }

    private fun mergeWords(segments: List<TranscriptCue>): List<TranscriptCue> {
        val merged = mutableListOf<TranscriptCue>()
        var current: TranscriptCue? = null
        var speaker: String? = null

        fun flush() {
            current?.let { merged += it }
            current = null
        }

        for (segment in segments) {
            val segmentSpeaker = segment.speaker ?: speaker
            val open = current
            if (open != null) {
                val speakerChanged = segment.speaker != null && segment.speaker != open.speaker
                val gap = segment.startMs - open.endMs > MERGE_GAP_MS
                val tooLong = open.text.length > MERGE_MAX_CHARS
                if (speakerChanged || gap || tooLong) flush()
            }
            val target = current
            current = if (target == null) {
                segment.copy(speaker = segmentSpeaker)
            } else {
                target.copy(endMs = maxOf(target.endMs, segment.endMs), text = joinWords(target.text, segment.text))
            }
            speaker = segmentSpeaker
            if (sentenceEndRegex.containsMatchIn(segment.text)) flush()
        }
        flush()
        return merged
    }

    private fun joinWords(left: String, right: String): String =
        if (right.first() in attachedPunctuation || left.endsWith('-') || left.endsWith('(')) left + right else "$left $right"

    fun parseHtml(content: String): ParsedTranscript = untimed(htmlToPlainText(content))

    fun parsePlain(content: String): ParsedTranscript = untimed(content)

    private fun untimed(text: String): ParsedTranscript {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParsedTranscript(emptyList(), false)
        val paragraphs = trimmed.split(blankLineRegex)
            .map { paragraph -> paragraph.lines().joinToString("\n") { it.trim() }.trim() }
            .filter { it.isNotEmpty() }
        return ParsedTranscript(paragraphs.map { TranscriptCue(0L, 0L, null, it) }, timed = false)
    }

    private fun blocks(content: String): List<String> =
        content.split(blankLineRegex).map { it.trim('\n') }.filter { it.isNotBlank() }

    private fun cleanText(raw: String): String {
        val stripped = htmlToPlainText(raw.replace('\n', ' '))
        return whitespaceRegex.replace(stripped, " ").trim()
    }

    fun parseTimestamp(value: String): Long? {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size !in 1..3) return null
        val secondsPart = parts.last()
        val seconds = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', "")
        val millis = if (fraction.isEmpty()) 0L else (fraction.padEnd(3, '0').take(3).toLongOrNull() ?: return null)
        val minutes = if (parts.size >= 2) parts[parts.size - 2].toLongOrNull() ?: return null else 0L
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.seconds(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.doubleOrNull?.let { return (it * 1000).roundToLong() }
        return parseTimestamp(primitive.content)
    }
}
