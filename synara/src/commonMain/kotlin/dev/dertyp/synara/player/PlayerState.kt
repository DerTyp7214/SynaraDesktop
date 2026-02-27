@file:UseContextualSerialization(PlatformUUID::class)
package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RepeatMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
data class PlayerState(
    val queue: List<QueueEntry> = emptyList(),
    val originalQueue: List<QueueEntry> = emptyList(),
    val source: PlaybackSource? = null,
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleMode: Boolean = false,
    val lastPosition: Long = 0L
)
