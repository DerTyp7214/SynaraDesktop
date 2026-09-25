package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PodcastEpisode

val PodcastEpisode.artworkId: PlatformUUID?
    get() = imageId ?: showImageId

val PodcastEpisode.isPlayed: Boolean
    get() = progress?.completed == true

val PodcastEpisode.positionMs: Long
    get() = progress?.positionMs?.coerceAtLeast(0L) ?: 0L

val PodcastEpisode.startPositionMs: Long
    get() = if (isPlayed) 0L else positionMs

val PodcastEpisode.knownDurationMs: Long?
    get() = (durationMs ?: progress?.durationMs)?.takeIf { it > 0 }

val PodcastEpisode.remainingMs: Long?
    get() = knownDurationMs?.let { (it - positionMs).coerceAtLeast(0L) }

val PodcastEpisode.isInProgress: Boolean
    get() = !isPlayed && positionMs > 0

val PodcastEpisode.progressFraction: Float
    get() {
        if (isPlayed) return 1f
        val total = knownDurationMs ?: return 0f
        return (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

private val blockBreakRegex = Regex(
    "<\\s*(br|/p|/div|/li|/h[1-6]|/blockquote|/tr|/ul|/ol|p|div|li|h[1-6]|blockquote|tr|ul|ol)(\\s[^>]*)?/?\\s*>",
    RegexOption.IGNORE_CASE
)
private val listItemRegex = Regex("<\\s*li(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val tagRegex = Regex("<[^>]+>")
private val numericEntityRegex = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
private val namedEntityRegex = Regex("&([a-zA-Z]+);")
private val spacesRegex = Regex("[ \\t\\u00A0]+")
private val blankLinesRegex = Regex("\\n{3,}")

private val namedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "ndash" to "–",
    "mdash" to "—",
    "hellip" to "…",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
    "bull" to "•",
    "middot" to "·",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "euro" to "€",
    "auml" to "ä",
    "ouml" to "ö",
    "uuml" to "ü",
    "Auml" to "Ä",
    "Ouml" to "Ö",
    "Uuml" to "Ü",
    "szlig" to "ß",
)

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    var text = html.replace("\r\n", "\n").replace('\r', '\n')
    if (!text.contains('<') && !text.contains('&')) return text.trim()
    if (text.contains('<')) text = text.replace(Regex("\\s*\\n\\s*"), " ")
    text = listItemRegex.replace(text, "\n• ")
    text = blockBreakRegex.replace(text) { match ->
        when (match.groupValues[1].lowercase()) {
            "br", "/li" -> "\n"
            else -> "\n\n"
        }
    }
    text = tagRegex.replace(text, "")
    text = numericEntityRegex.replace(text) { match ->
        val raw = match.groupValues[1]
        val code = if (raw.startsWith("x") || raw.startsWith("X")) raw.substring(1).toIntOrNull(16) else raw.toIntOrNull()
        if (code == null || code !in 0..0x10FFFF) match.value
        else if (code <= 0xFFFF) code.toChar().toString()
        else {
            val shifted = code - 0x10000
            charArrayOf((0xD800 + (shifted shr 10)).toChar(), (0xDC00 + (shifted and 0x3FF)).toChar()).concatToString()
        }
    }
    text = namedEntityRegex.replace(text) { match -> namedEntities[match.groupValues[1]] ?: match.value }
    text = text.lines().joinToString("\n") { spacesRegex.replace(it, " ").trim() }
    text = blankLinesRegex.replace(text, "\n\n")
    return text.trim()
}
