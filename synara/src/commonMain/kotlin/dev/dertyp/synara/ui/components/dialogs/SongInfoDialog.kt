package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.core.joinArtists
import dev.dertyp.data.UserSong
import dev.dertyp.data.effectiveAudio
import dev.dertyp.synara.core.displayTags
import dev.dertyp.synara.ui.components.channelLabel
import dev.dertyp.synara.ui.components.formatFileSize
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@Composable
fun SongInfoDialog(
    isOpen: Boolean,
    song: UserSong,
    onDismissRequest: () -> Unit
) {
    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.song_info_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoItem(stringResource(Res.string.metadata_title), song.title)
                if (song.tags.displayTags.isNotEmpty()) {
                    InfoItem(
                        stringResource(Res.string.metadata_tags),
                        song.tags.displayTags.joinToString { it.label }
                    )
                }
                InfoItem(stringResource(Res.string.metadata_artist), song.artists.joinArtists())
                InfoItem(stringResource(Res.string.metadata_album), song.album?.name ?: "-")
                InfoItem(
                    stringResource(Res.string.metadata_release_date),
                    song.releaseDate?.toString() ?: "-"
                )
                InfoItem(
                    stringResource(Res.string.metadata_track_disc),
                    "${song.trackNumber} / ${song.discNumber}"
                )
                InfoItem(stringResource(Res.string.metadata_copyright), song.copyright.ifBlank { "-" })
                val audio = song.effectiveAudio
                InfoItem(
                    stringResource(Res.string.metadata_quality),
                    "${(audio?.sampleRate ?: 0) / 1000}kHz / ${audio?.bitsPerSample ?: 0}bit / ${audio?.bitRate ?: 0}kbps"
                )
                InfoItem(
                    stringResource(Res.string.metadata_codec),
                    audio?.codec?.ifBlank { null }?.uppercase() ?: "-"
                )
                InfoItem(
                    stringResource(Res.string.metadata_channels),
                    channelLabel(audio?.channels ?: 0)
                )
                InfoItem(
                    stringResource(Res.string.metadata_file_size),
                    formatFileSize(audio?.fileSize ?: 0L)
                )
                InfoItem(stringResource(Res.string.metadata_url), song.originalUrl.ifBlank { "-" })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.ok))
            }
        }
    )
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
