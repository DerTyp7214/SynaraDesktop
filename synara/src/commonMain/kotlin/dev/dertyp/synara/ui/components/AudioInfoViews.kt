package dev.dertyp.synara.ui.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.audio_channels_count
import synara.synara.generated.resources.audio_channels_mono
import synara.synara.generated.resources.audio_channels_stereo

@Composable
fun channelLabel(channels: Int): String = when {
    channels <= 0 -> "-"
    channels == 1 -> stringResource(Res.string.audio_channels_mono)
    channels == 2 -> stringResource(Res.string.audio_channels_stereo)
    channels == 6 -> "5.1"
    channels == 8 -> "7.1"
    else -> stringResource(Res.string.audio_channels_count, channels)
}
