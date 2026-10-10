package dev.dertyp.synara.player.audio.decode

internal object OpusEndTrim {
    fun totalFrames(granulePosition: Long, preSkip: Int): Long? =
        if (granulePosition < 0 || granulePosition < preSkip) null else granulePosition - preSkip

    fun framesToKeep(totalFrames: Long?, framesSoFar: Long, decodedFrames: Int): Int {
        val decoded = decodedFrames.coerceAtLeast(0)
        if (totalFrames == null) return decoded
        return (totalFrames - framesSoFar).coerceIn(0L, decoded.toLong()).toInt()
    }
}
