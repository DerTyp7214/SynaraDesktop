package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.PlayPauseIcon
import dev.dertyp.synara.ui.components.SynaraLargeFab
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.next_song
import synara.synara.generated.resources.previous

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    currentSongExists: Boolean,
    onSkipPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = onSkipPrevious,
            enabled = currentSongExists
        ) {
            Icon(
                SynaraIcons.SkipPrevious.get(),
                contentDescription = stringResource(Res.string.previous)
            )
        }

        SynaraLargeFab(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
        ) {
            PlayPauseIcon(
                isPlaying = isPlaying,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(
            onClick = onSkipNext,
            enabled = currentSongExists
        ) {
            Icon(
                SynaraIcons.SkipNext.get(),
                contentDescription = stringResource(Res.string.next_song)
            )
        }
    }
}
