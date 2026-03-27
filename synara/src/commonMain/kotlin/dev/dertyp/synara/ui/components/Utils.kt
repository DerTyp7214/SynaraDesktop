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
