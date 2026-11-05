package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.artist_top_songs_plays
import synara.synara.generated.resources.artist_top_songs_popular
import synara.synara.generated.resources.artist_top_songs_your_top

@Composable
fun ArtistTopSongOrderChips(
    order: ArtistTopSongOrder,
    onOrderChange: (ArtistTopSongOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistTopSongOrder.entries.forEach { entry ->
            FilterChip(
                selected = order == entry,
                onClick = { onOrderChange(entry) },
                elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                label = {
                    Text(
                        stringResource(
                            when (entry) {
                                ArtistTopSongOrder.GLOBAL_LISTENS -> Res.string.artist_top_songs_popular
                                ArtistTopSongOrder.OWN_LISTENS -> Res.string.artist_top_songs_your_top
                            }
                        )
                    )
                },
                modifier = Modifier.testTag("artist_top_songs_order_${entry.name}")
            )
        }
    }
}

@Composable
fun ArtistTopSong.playsText(order: ArtistTopSongOrder): String? {
    val count = when (order) {
        ArtistTopSongOrder.GLOBAL_LISTENS -> globalListenCount
        ArtistTopSongOrder.OWN_LISTENS -> ownListenCount
    } ?: return null
    return stringResource(Res.string.artist_top_songs_plays, formatCompactCount(count))
}
