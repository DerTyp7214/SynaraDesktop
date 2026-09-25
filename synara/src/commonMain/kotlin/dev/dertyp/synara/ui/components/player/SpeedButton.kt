package dev.dertyp.synara.ui.components.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.podcast_speed
import synara.synara.generated.resources.podcast_speed_value
import kotlin.math.abs
import kotlin.math.roundToInt

val SpeedButtonWidth = 64.dp

fun formatPlaybackSpeed(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0 -> "$whole"
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

@Composable
fun SpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    speeds: List<Float> = PodcastPlayer.SPEEDS
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = abs(speed - 1f) < 0.001f

    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.width(SpeedButtonWidth),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (isDefault) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariantDistinct()
            )
        ) {
            Text(
                text = stringResource(Res.string.podcast_speed_value, formatPlaybackSpeed(speed)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        SynaraMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            speeds.forEach { value ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.podcast_speed_value, formatPlaybackSpeed(value))) },
                    onClick = {
                        onSpeedChange(value)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            SynaraIcons.PlaybackSpeed.get(),
                            contentDescription = stringResource(Res.string.podcast_speed),
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (abs(value - speed) < 0.001f) {
                        { Icon(SynaraIcons.Confirm.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null
                )
            }
        }
    }
}
