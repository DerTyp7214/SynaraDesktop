package dev.dertyp.synara.mixes

import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.data.UserSong
import dev.dertyp.services.IListeningStatsService
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.utils.currentTimezoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MixSource {
    @Serializable
    @SerialName("recent")
    data class Recent(val window: RecommendationWindow) : MixSource

    @Serializable
    @SerialName("mood")
    data class Mood(val mood: String) : MixSource
}

@Serializable
sealed interface MixSpec {
    val size: Int
    val key: String

    @Serializable
    @SerialName("your_mix")
    data class YourMix(val source: MixSource, override val size: Int) : MixSpec {
        override val key: String
            get() = when (source) {
                is MixSource.Recent -> "mix:${source.window.name}"
                is MixSource.Mood -> "mood:${source.mood}"
            }
    }

    @Serializable
    @SerialName("top_songs")
    data class TopSongs(val range: StatsRange, val order: TopOrder, override val size: Int) : MixSpec {
        override val key: String
            get() = "top:${range.name}:${order.name}"
    }
}

@Serializable
enum class MixType { YOUR_MIX, TOP_SONGS }

val MixSpec.type: MixType
    get() = when (this) {
        is MixSpec.YourMix -> MixType.YOUR_MIX
        is MixSpec.TopSongs -> MixType.TOP_SONGS
    }

val MIX_SIZE_OPTIONS = listOf(10, 25, 50, 100)

const val DEFAULT_MIX_SIZE = 50

val MixType.defaultSpec: MixSpec
    get() = when (this) {
        MixType.YOUR_MIX -> MixSpec.YourMix(MixSource.Recent(RecommendationWindow.WEEK), DEFAULT_MIX_SIZE)
        MixType.TOP_SONGS -> MixSpec.TopSongs(StatsRange.WEEK, TopOrder.LISTENED_MS, DEFAULT_MIX_SIZE)
    }

val DEFAULT_MIX_OPTIONS: Map<MixType, MixSpec> = MixType.entries.associateWith { it.defaultSpec }

suspend fun MixSpec.fetch(recs: IRecommendationService, stats: IListeningStatsService): List<UserSong> = when (this) {
    is MixSpec.YourMix -> when (source) {
        is MixSource.Recent -> recs.getMix(source.window, size)
        is MixSource.Mood -> recs.getMoodPlaylist(source.mood, size)
    }
    is MixSpec.TopSongs -> stats.getTopSongs(range, currentTimezoneId().ifBlank { "UTC" }, size, order)
}
