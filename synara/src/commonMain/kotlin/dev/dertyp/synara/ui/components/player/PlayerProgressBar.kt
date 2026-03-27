package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.components.formatDuration

@Composable
fun PlayerProgressBar(
    currentPosition: Long,
    duration: Long,
    currentSongExists: Boolean,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatDuration(currentPosition.coerceAtMost(duration)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 40.dp)
        )

        Slider(
            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
            onValueChange = onSeek,
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(12.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            enabled = currentSongExists
        )

        Text(
            formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = TextAlign.End
        )
    }
}
