package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.StatsRange
import dev.dertyp.data.TopOrder
import dev.dertyp.synara.mixes.MixSource
import dev.dertyp.synara.mixes.MixSpec
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

val MIX_STATS_RANGES = listOf(StatsRange.DAY, StatsRange.WEEK, StatsRange.MONTH, StatsRange.YEAR, StatsRange.ALL_TIME)

@Composable
fun RecommendationWindow.label(): String = stringResource(
    when (this) {
        RecommendationWindow.DAY -> Res.string.stats_range_day
        RecommendationWindow.WEEK -> Res.string.stats_range_week
        RecommendationWindow.MONTH -> Res.string.stats_range_month
    }
)

@Composable
fun StatsRange.label(): String = stringResource(
    when (this) {
        StatsRange.DAY -> Res.string.stats_range_day
        StatsRange.WEEK -> Res.string.stats_range_week
        StatsRange.LAST_WEEK -> Res.string.stats_range_last_week
        StatsRange.MONTH -> Res.string.stats_range_month
        StatsRange.LAST_MONTH -> Res.string.stats_range_last_month
        StatsRange.YEAR -> Res.string.stats_range_year
        StatsRange.LAST_YEAR -> Res.string.stats_range_last_year
        StatsRange.ALL_TIME -> Res.string.stats_range_all_time
    }
)

@Composable
fun TopOrder.label(): String = stringResource(
    when (this) {
        TopOrder.LISTEN_COUNT -> Res.string.stats_order_listens
        TopOrder.LISTENED_MS -> Res.string.stats_order_time
    }
)

@Composable
fun MixSource.label(): String = when (this) {
    is MixSource.Recent -> window.label()
    is MixSource.Mood -> mood
}

@Composable
fun MixSpec.title(): String = stringResource(
    when (this) {
        is MixSpec.YourMix -> Res.string.your_mix
        is MixSpec.TopSongs -> Res.string.top_songs_mix
    }
)

@Composable
fun MixSpec.optionsLabel(): String = when (this) {
    is MixSpec.YourMix -> source.label()
    is MixSpec.TopSongs -> "${range.label()} · ${order.label()}"
}

@Composable
fun MixSpec.cardTitle(): String = when (this) {
    is MixSpec.YourMix -> when (source) {
        is MixSource.Recent -> "${title()} · ${source.label()}"
        is MixSource.Mood -> source.mood
    }
    is MixSpec.TopSongs -> "${title()} · ${range.label()}"
}

val MixSpec.icon: SynaraIcons
    get() = when (this) {
        is MixSpec.YourMix -> when (source) {
            is MixSource.Recent -> SynaraIcons.Discovery
            is MixSource.Mood -> SynaraIcons.Songs
        }
        is MixSpec.TopSongs -> SynaraIcons.Stats
    }

@Composable
fun MixIconTile(spec: MixSpec, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            spec.icon.get(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size / 2)
        )
    }
}

@Composable
fun MixCard(
    spec: MixSpec,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MixIconTile(spec = spec, size = 56.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
