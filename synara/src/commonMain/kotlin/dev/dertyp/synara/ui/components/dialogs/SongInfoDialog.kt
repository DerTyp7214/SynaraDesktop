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
                InfoItem(
                    stringResource(Res.string.metadata_quality),
                    "${song.sampleRate / 1000}kHz / ${song.bitsPerSample}bit / ${song.bitRate}kbps"
                )
                InfoItem(
                    stringResource(Res.string.metadata_file_size),
                    formatFileSize(song.fileSize)
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

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "${"%.2f".format(gb)} GB"
        mb >= 1 -> "${"%.2f".format(mb)} MB"
        kb >= 1 -> "${"%.2f".format(kb)} KB"
        else -> "$bytes Bytes"
    }
}
