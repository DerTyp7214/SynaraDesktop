@file:UseContextualSerialization(PlatformUUID::class)
package dev.dertyp.synara.podcast

import dev.dertyp.PlatformUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
data class PodcastPlayerState(
    val episodeIds: List<PlatformUUID> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val savedAt: Long = 0L
)
