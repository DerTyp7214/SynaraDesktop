package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.MergeArtistDialog
import dev.dertyp.synara.ui.components.dialogs.SplitArtistDialog
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.launch
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
    snackbarManager: SnackbarManager = koinInject()
) {
    val scope = rememberCoroutineScope()
    val user by globalState.user.collectAsState()
    
    var showMergeDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }

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
                text = artist.name,
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

        if (user?.isAdmin == true) {
            HorizontalDivider()

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
                        snackbarManager.showSnackbar("Artist merged successfully")
                    } else {
                        snackbarManager.showSnackbar("Failed to merge artist")
                    }
                } catch (e: Exception) {
                    snackbarManager.showSnackbar("Error merging artist: ${e.message}")
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
                        snackbarManager.showSnackbar("Artist split into ${result.size} artists")
                    } else {
                        snackbarManager.showSnackbar("Failed to split artist")
                    }
                } catch (e: Exception) {
                    snackbarManager.showSnackbar("Error splitting artist: ${e.message}")
                }
            }
        }
    )
}
