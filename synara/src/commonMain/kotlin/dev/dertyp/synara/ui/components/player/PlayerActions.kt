package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.data.RepeatMode
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.VolumeControl
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.repeat
import synara.synara.generated.resources.shuffle

@Composable
fun PlayerActions(
    shuffleMode: Boolean,
    repeatMode: RepeatMode,
    volume: Float,
    currentSongExists: Boolean,
    isCompact: Boolean,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            enabled = currentSongExists
        ) {
            Icon(
                SynaraIcons.Shuffle.get(),
                contentDescription = stringResource(Res.string.shuffle),
                tint = if (shuffleMode) MaterialTheme.colorScheme.onSurfaceVariantDistinct() else LocalContentColor.current
            )
        }
        IconButton(
            onClick = onToggleRepeat,
            enabled = currentSongExists
        ) {
            Icon(
                when (repeatMode) {
                    RepeatMode.ONE -> SynaraIcons.RepeatOne.get()
                    else -> SynaraIcons.Repeat.get()
                },
                contentDescription = stringResource(Res.string.repeat),
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariantDistinct() else LocalContentColor.current
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        VolumeControl(
            volume = volume,
            onVolumeChange = onVolumeChange,
            isCompact = isCompact
        )
    }
}
