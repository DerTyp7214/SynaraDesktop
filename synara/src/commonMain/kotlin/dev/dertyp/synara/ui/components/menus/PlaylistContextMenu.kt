package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.screens.SimilarSongsScreen
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.CreatePlaylistDialog
import dev.dertyp.synara.ui.components.dialogs.PlaylistPickerDialog
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsDialog
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsSeed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.add_to_playlist
import synara.synara.generated.resources.add_to_queue
import synara.synara.generated.resources.get_similar_songs
import synara.synara.generated.resources.menu_download
import synara.synara.generated.resources.play_next
import synara.synara.generated.resources.remove_download

@Composable
fun PlaylistContextMenu(
    playlistId: PlatformUUID,
    playlistName: String,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    playerModel: PlayerModel = koinInject(),
    downloadManager: IDownloadManager? = koinInject<IDownloadManager?>(),
) {
    var showPlaylistPickerDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showSimilarSongsDialog by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current

    SynaraMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 200.dp)
        ) {
            Text(
                text = playlistName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            onClick = {
                playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Playlist(playlistId)))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            onClick = {
                playerModel.playNext(PlaybackQueue(source = PlaybackSource.Playlist(playlistId)))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.PlayNext.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_playlist)) },
            onClick = {
                showPlaylistPickerDialog = true
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.get_similar_songs)) },
            onClick = {
                showSimilarSongsDialog = true
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.Discovery.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        downloadManager?.let { dm ->
            val isDownloaded by dm.isPlaylistDownloaded(playlistId).collectAsState(false)

            if (isDownloaded) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_download)) },
                    onClick = {
                        dm.removePlaylist(playlistId)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Delete.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.menu_download)) },
                    onClick = {
                        dm.downloadPlaylist(playlistId)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Download.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
        }
    }

    PlaylistPickerDialog(
        isOpen = showPlaylistPickerDialog,
        onPlaylistSelected = { targetPlaylist ->
            playerModel.addSongsToPlaylist(targetPlaylist.id, PlaybackQueue(source = PlaybackSource.Playlist(playlistId)))
        },
        onCreatePlaylist = {
            showPlaylistPickerDialog = false
            showCreatePlaylistDialog = true
        },
        onDismissRequest = { showPlaylistPickerDialog = false }
    )

    CreatePlaylistDialog(
        isOpen = showCreatePlaylistDialog,
        onConfirm = { name ->
            playerModel.createPlaylist(name, PlaybackQueue(source = PlaybackSource.Playlist(playlistId)))
        },
        onDismissRequest = { showCreatePlaylistDialog = false }
    )

    SimilarSongsDialog(
        isOpen = showSimilarSongsDialog,
        seed = SimilarSongsSeed.Playlist(playlistId, playlistName),
        onConfirm = { criterion, limit ->
            navigator?.push(SimilarSongsScreen(SimilarSongsSeed.Playlist(playlistId, playlistName), criterion, limit))
        },
        onDismissRequest = { showSimilarSongsDialog = false }
    )
}
