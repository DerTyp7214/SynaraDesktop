package dev.dertyp.synara.player.audio

import kotlinx.coroutines.flow.Flow
import java.nio.ShortBuffer

class DecodedStream(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val startMs: Long,
    val durationMs: Long?,
    val bitRate: Long?,
    val pcmFlow: Flow<ShortBuffer>
)
