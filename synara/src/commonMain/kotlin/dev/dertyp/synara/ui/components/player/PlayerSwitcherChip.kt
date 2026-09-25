package dev.dertyp.synara.ui.components.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.player.ActivePlayer
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.podcast_player_switch_music
import synara.synara.generated.resources.podcast_player_switch_podcasts

val PlayerSwitcherChipWidth = 72.dp

@Composable
fun PlayerSwitcherChip(
    active: ActivePlayer,
    onSelect: (ActivePlayer) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(end = 8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwitcherSegment(
                icon = SynaraIcons.Songs,
                description = stringResource(Res.string.podcast_player_switch_music),
                selected = active == ActivePlayer.SONGS,
                onClick = { onSelect(ActivePlayer.SONGS) }
            )
            SwitcherSegment(
                icon = SynaraIcons.Podcast,
                description = stringResource(Res.string.podcast_player_switch_podcasts),
                selected = active == ActivePlayer.PODCAST,
                onClick = { onSelect(ActivePlayer.PODCAST) }
            )
        }
    }
}

@Composable
private fun SwitcherSegment(
    icon: SynaraIcons,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "switcherSegmentBackground"
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "switcherSegmentTint"
    )

    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 28.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = !selected, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(if (selected) PointerIcon.Default else PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon.get(),
            contentDescription = description,
            modifier = Modifier.size(16.dp),
            tint = tint
        )
    }
}
