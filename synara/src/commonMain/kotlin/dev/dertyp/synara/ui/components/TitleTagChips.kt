package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.data.TitleTag
import dev.dertyp.synara.core.displayTags
import dev.dertyp.synara.core.icon
import dev.dertyp.synara.core.localizedName

enum class TitleTagChipStyle { Compact, Detailed }

@Composable
fun TitleTagChip(
    tag: TitleTag,
    style: TitleTagChipStyle = TitleTagChipStyle.Compact,
    dense: Boolean = false,
    modifier: Modifier = Modifier
) {
    TitleTagChipSurface(modifier = modifier, dense = dense) {
        when (style) {
            TitleTagChipStyle.Compact -> Text(
                text = tag.kind.localizedName(),
                style = chipTextStyle(dense),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            TitleTagChipStyle.Detailed -> Row(
                horizontalArrangement = Arrangement.spacedBy(if (dense) 3.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tag.kind.icon.get(),
                    contentDescription = null,
                    modifier = Modifier.size(if (dense) 8.dp else 10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tag.label,
                    style = chipTextStyle(dense),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun chipTextStyle(dense: Boolean) =
    if (dense) MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp)
    else MaterialTheme.typography.labelSmall

@Composable
private fun TitleTagChipSurface(
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = if (dense) Modifier.padding(horizontal = 6.dp, vertical = 0.dp)
            else Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleTagChips(
    tags: List<TitleTag>,
    style: TitleTagChipStyle = TitleTagChipStyle.Compact,
    compactLimit: Int = 2,
    singleLine: Boolean = false,
    dense: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shown = tags.displayTags
    if (shown.isEmpty()) return

    when (style) {
        TitleTagChipStyle.Compact -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shown.take(compactLimit).forEach { TitleTagChip(it, TitleTagChipStyle.Compact, dense) }
            if (shown.size > compactLimit) {
                TitleTagChipSurface(dense = dense) {
                    Text(
                        text = "+${shown.size - compactLimit}",
                        style = chipTextStyle(dense),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        TitleTagChipStyle.Detailed -> if (singleLine) {
            Row(
                modifier = modifier.clipToBounds(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                shown.forEach { TitleTagChip(it, TitleTagChipStyle.Detailed, dense) }
            }
        } else {
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                shown.forEach { TitleTagChip(it, TitleTagChipStyle.Detailed) }
            }
        }
    }
}
