package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.Album
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlaybackSource
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.ArtistListDialog
import dev.dertyp.synara.ui.components.dialogs.CreatePlaylistDialog
import dev.dertyp.synara.ui.components.dialogs.PlaylistPickerDialog
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun AlbumContextMenu(
    album: Album,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    playerModel: PlayerModel = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    downloadManager: IDownloadManager? = koinInject<IDownloadManager?>(),
) {
    var showArtistListDialog by remember { mutableStateOf(false) }
    var showPlaylistPickerDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current

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
                if (album.musicbrainzId != null) {
                    Icon(
                        SynaraIcons.MusicBrainz.get(),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        HorizontalDivider()

        if (album.artists.isNotEmpty()) {
            val isMultiple = album.artists.size > 1
            DropdownMenuItem(
                text = {
                    Text(
                        if (isMultiple) stringResource(Res.string.show_artists)
                        else stringResource(Res.string.show_artist)
                    )
                },
                onClick = {
                    if (isMultiple) {
                        showArtistListDialog = true
                    } else {
                        globalState.setPlayerExpanded(false)
                        navigator?.push(ArtistScreen(album.artists.first().id))
                    }
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.Artists.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            onClick = {
                playerModel.addToQueue(PlaybackQueue(source = PlaybackSource.Album(album.id)))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            onClick = {
                playerModel.playNext(PlaybackQueue(source = PlaybackSource.Album(album.id)))
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

        downloadManager?.let { dm ->
            val isDownloaded by dm.isAlbumDownloaded(album.id).collectAsState(false)

            if (isDownloaded) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_download)) },
                    onClick = {
                        dm.removeAlbum(album.id)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Delete.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.menu_download)) },
                    onClick = {
                        dm.downloadAlbum(album.id)
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Download.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.delete)) },
            onClick = { onDismissRequest() },
            leadingIcon = { Icon(SynaraIcons.Delete.get(), contentDescription = null, modifier = Modifier.size(20.dp)) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            )
        )
    }

    ArtistListDialog(
        isOpen = showArtistListDialog,
        artists = album.artists,
        onArtistClick = { artist ->
            globalState.setPlayerExpanded(false)
            navigator?.push(ArtistScreen(artist.id))
        },
        onDismissRequest = { showArtistListDialog = false }
    )

    PlaylistPickerDialog(
        isOpen = showPlaylistPickerDialog,
        onPlaylistSelected = { playlist ->
            playerModel.addSongsToPlaylist(playlist.id, PlaybackQueue(source = PlaybackSource.Album(album.id)))
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
            playerModel.createPlaylist(name, PlaybackQueue(source = PlaybackSource.Album(album.id)))
        },
        onDismissRequest = { showCreatePlaylistDialog = false }
    )
}
