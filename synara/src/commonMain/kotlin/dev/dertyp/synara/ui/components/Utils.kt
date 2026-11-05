package dev.dertyp.synara.ui.components

fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    return if (durationMs >= 3600000) {
        val hours = durationMs / 3600000
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatListenedTime(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

fun formatCompactCount(count: Long): String {
    val (divisor, suffix) = when {
        count >= 1_000_000_000 -> 1_000_000_000L to "B"
        count >= 1_000_000 -> 1_000_000L to "M"
        count >= 1_000 -> 1_000L to "K"
        else -> return count.toString()
    }
    val whole = count / divisor
    val tenth = (count % divisor) * 10 / divisor
    return if (whole >= 100 || tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
}

fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "${"%.2f".format(gb)} GB"
        mb >= 1 -> "${"%.2f".format(mb)} MB"
        kb >= 1 -> "${"%.2f".format(kb)} KB"
        else -> "$bytes Bytes"
    }
}
