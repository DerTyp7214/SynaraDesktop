package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.components.dialogs.SongInfoDialog
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun SongContextMenu(
    song: UserSong,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    playerModel: PlayerModel = koinInject(),
    isInQueue: Boolean = false,
    isInPlaylist: Boolean = false,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null
) {
    var showInfoDialog by remember { mutableStateOf(false) }

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
                text = song.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.info)) },
            onClick = {
                showInfoDialog = true
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.show_artists)) },
            onClick = { onDismissRequest() },
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.show_album)) },
            onClick = { onDismissRequest() },
            leadingIcon = { Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            onClick = {
                playerModel.addToQueue(song)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            onClick = {
                playerModel.playNext(song)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_playlist)) },
            onClick = { onDismissRequest() },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        if (isInPlaylist || isInQueue) {
            HorizontalDivider()
            if (isInPlaylist) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_from_playlist)) },
                    onClick = {
                        onRemoveFromPlaylist?.invoke()
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
            if (isInQueue) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_from_queue)) },
                    onClick = {
                        onRemoveFromQueue?.invoke()
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
        }

        HorizontalDivider()

        val isFavorite = song.isFavourite == true
        DropdownMenuItem(
            text = {
                Text(
                    if (isFavorite) stringResource(Res.string.remove_from_favorites)
                    else stringResource(Res.string.add_to_favorites)
                )
            },
            onClick = {
                playerModel.toggleLike(song)
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.delete)) },
            onClick = { onDismissRequest() },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            )
        )
    }

    SongInfoDialog(
        isOpen = showInfoDialog,
        song = song,
        onDismissRequest = { showInfoDialog = false }
    )
}
