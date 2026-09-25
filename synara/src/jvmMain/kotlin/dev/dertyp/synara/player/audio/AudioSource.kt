package dev.dertyp.synara.player.audio

import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.io.InputStream

interface AudioSource {
    val cacheKey: String
    val durationMsHint: Long?
    val bitRateHint: Long?
    val formatHint: String?

    suspend fun prepare(): Boolean = true

    suspend fun size(): Long

    suspend fun open(offset: Long, scope: CoroutineScope): InputStream?
}

class UnsupportedAudioFormatException(val format: String?) : IOException("Unsupported audio format: ${format ?: "unknown"}")

sealed class AudioLoadError {
    data object UnsupportedFormat : AudioLoadError()
    data object Unavailable : AudioLoadError()
    data class Failed(val message: String?) : AudioLoadError()
}
