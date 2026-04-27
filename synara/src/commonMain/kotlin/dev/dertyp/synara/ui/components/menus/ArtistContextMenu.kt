package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.MergeArtistDialog
import dev.dertyp.synara.ui.components.dialogs.SetArtistGroupDialog
import dev.dertyp.synara.ui.components.dialogs.SplitArtistDialog
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun ArtistContextMenu(
    artist: Artist,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    playerModel: PlayerModel = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    artistService: IArtistService = koinInject(),
    snackbarManager: SnackbarManager = koinInject(),
    downloadManager: IDownloadManager? = koinInject<IDownloadManager?>(),
) {
    val scope = rememberCoroutineScope()
    val user by globalState.user.collectAsState()
    
    var showMergeDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showSetGroupDialog by remember { mutableStateOf(false) }

    SynaraMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 240.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (artist.musicbrainzId != null) {
                    Icon(
                        SynaraIcons.MusicBrainz.get(),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            onClick = {
                playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Artist(artist.id)))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            onClick = {
                playerModel.playNext(PlaybackQueue(source = PlaybackSource.Artist(artist.id)))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.PlayNext.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        downloadManager?.let { dm ->
            val isDownloaded by dm.isArtistDownloaded(artist.id).collectAsState(false)

            if (isDownloaded) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_download)) },
                    onClick = {
                        dm.removeArtist(artist.id)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Delete.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.menu_download)) },
                    onClick = {
                        dm.downloadArtist(artist.id)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Download.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
        }

        if (user?.isAdmin == true) {
            HorizontalDivider()

            DropdownMenuItem(
                text = { Text(stringResource(Res.string.set_artist_group_title)) },
                onClick = {
                    showSetGroupDialog = true
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.Artists.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )

            DropdownMenuItem(
                text = { Text(stringResource(Res.string.menu_merge_artist)) },
                onClick = {
                    showMergeDialog = true
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.ArtistMerge.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )

            DropdownMenuItem(
                text = { Text(stringResource(Res.string.menu_split_artist)) },
                onClick = {
                    showSplitDialog = true
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.ArtistSplit.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }
    }

    MergeArtistDialog(
        isOpen = showMergeDialog,
        artist = artist,
        onDismissRequest = { showMergeDialog = false },
        onMerge = { mergeArtists ->
            scope.launch {
                try {
                    val result = artistService.mergeArtists(mergeArtists)
                    if (result != null) {
                        snackbarManager.showSnackbar(getString(Res.string.artist_merged_success))
                    } else {
                        snackbarManager.showSnackbar(getString(Res.string.artist_merged_failed))
                    }
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.artist_merged_error, e.message ?: "Unknown error"))
                }
            }
        }
    )

    SplitArtistDialog(
        isOpen = showSplitDialog,
        artist = artist,
        onDismissRequest = { showSplitDialog = false },
        onSplit = { splitArtist ->
            scope.launch {
                try {
                    val result = artistService.splitArtist(splitArtist)
                    if (result.isNotEmpty()) {
                        snackbarManager.showSnackbar(getString(Res.string.artist_split_success, result.size))
                    } else {
                        snackbarManager.showSnackbar(getString(Res.string.artist_split_failed))
                    }
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.artist_split_error, e.message ?: "Unknown error"))
                }
            }
        }
    )

    SetArtistGroupDialog(
        isOpen = showSetGroupDialog,
        artist = artist,
        onDismissRequest = { showSetGroupDialog = false },
        onSave = { artists ->
            scope.launch {
                try {
                    val result = artistService.setGroup(artist.id, artists?.map { it.id } ?: emptyList())
                    if (result != null) {
                        snackbarManager.showSnackbar(getString(Res.string.artist_group_updated_success))
                    } else {
                        snackbarManager.showSnackbar(getString(Res.string.artist_group_updated_failed))
                    }
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.artist_group_updated_error, e.message ?: "Unknown error"))
                }
            }
        }
    )
}
