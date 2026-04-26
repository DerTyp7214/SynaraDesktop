package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.screens.AlbumScreen
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.screens.MetadataEditScreen
import dev.dertyp.synara.screens.SimilarSongsScreen
import dev.dertyp.synara.services.DownloadStatus
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.ArtistListDialog
import dev.dertyp.synara.ui.components.dialogs.CreatePlaylistDialog
import dev.dertyp.synara.ui.components.dialogs.PlaylistPickerDialog
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsDialog
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsSeed
import dev.dertyp.synara.ui.components.dialogs.SongInfoDialog
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.add_to_favorites
import synara.synara.generated.resources.add_to_playlist
import synara.synara.generated.resources.add_to_queue
import synara.synara.generated.resources.cancel_download
import synara.synara.generated.resources.delete
import synara.synara.generated.resources.edit_metadata
import synara.synara.generated.resources.get_similar_songs
import synara.synara.generated.resources.info
import synara.synara.generated.resources.menu_download
import synara.synara.generated.resources.play_next
import synara.synara.generated.resources.remove_download
import synara.synara.generated.resources.remove_from_favorites
import synara.synara.generated.resources.remove_from_playlist
import synara.synara.generated.resources.remove_from_queue
import synara.synara.generated.resources.show_album
import synara.synara.generated.resources.show_artist
import synara.synara.generated.resources.show_artists

@Composable
fun SongContextMenu(
    song: UserSong,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    playerModel: PlayerModel = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    downloadManager: IDownloadManager? = koinInject<IDownloadManager?>(),
    isInQueue: Boolean = false,
    isInPlaylist: Boolean = false,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    var showArtistListDialog by remember { mutableStateOf(false) }
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
                .widthIn(max = 240.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.musicBrainzId != null) {
                    Icon(
                        SynaraIcons.MusicBrainz.get(),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = song.title,
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
            text = { Text(stringResource(Res.string.info)) },
            onClick = {
                showInfoDialog = true
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.Info.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.edit_metadata)) },
            onClick = {
                globalState.setPlayerExpanded(false)
                navigator?.push(MetadataEditScreen(song.id))
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.Edit.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )

        if (song.artists.isNotEmpty()) {
            val isMultiple = song.artists.size > 1
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
                        navigator?.push(ArtistScreen(song.artists.first().id))
                    }
                    onDismissRequest()
                },
                leadingIcon = {
                    Icon(
                        SynaraIcons.Artists.get(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        song.album?.let { album ->
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.show_album)) },
                onClick = {
                    globalState.setPlayerExpanded(false)
                    navigator?.push(AlbumScreen(album.id))
                    onDismissRequest()
                },
                leadingIcon = {
                    Icon(
                        SynaraIcons.Albums.get(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_queue)) },
            onClick = {
                playerModel.addToQueue(song)
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.AddToPlaylist.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.play_next)) },
            onClick = {
                playerModel.playNext(song)
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.PlayNext.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.add_to_playlist)) },
            onClick = {
                showPlaylistPickerDialog = true
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.AddToPlaylist.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.get_similar_songs)) },
            onClick = {
                showSimilarSongsDialog = true
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.Discovery.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )

        downloadManager?.let { dm ->
            val downloadStatus by dm.getDownloadStatus(song.id).collectAsState(DownloadStatus.NotDownloaded)

            when (downloadStatus) {
                DownloadStatus.NotDownloaded -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_download)) },
                        onClick = {
                            dm.downloadSong(song.id)
                            onDismissRequest()
                        },
                        leadingIcon = {
                            Icon(
                                SynaraIcons.Download.get(),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
                DownloadStatus.Queued, DownloadStatus.Downloading -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.cancel_download)) },
                        onClick = {
                            dm.removeSong(song.id)
                            onDismissRequest()
                        },
                        leadingIcon = {
                            Icon(
                                SynaraIcons.Close.get(),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
                DownloadStatus.Downloaded -> {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.remove_download)) },
                        onClick = {
                            dm.removeSong(song.id)
                            onDismissRequest()
                        },
                        leadingIcon = {
                            Icon(
                                SynaraIcons.Delete.get(),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
        }

        if (isInPlaylist || isInQueue) {
            HorizontalDivider()
            if (isInPlaylist) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_from_playlist)) },
                    onClick = {
                        onRemoveFromPlaylist?.invoke()
                        onDismissRequest()
                    },
                    leadingIcon = {
                        Icon(
                            SynaraIcons.RemoveFromPlaylist.get(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
            if (isInQueue) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_from_queue)) },
                    onClick = {
                        onRemoveFromQueue?.invoke()
                        onDismissRequest()
                    },
                    leadingIcon = {
                        Icon(
                            SynaraIcons.RemoveFromQueue.get(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                    if (isFavorite) SynaraIcons.IsFavorite.get() else SynaraIcons.IsNotFavorite.get(),
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
            leadingIcon = {
                Icon(
                    SynaraIcons.Delete.get(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
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

    ArtistListDialog(
        isOpen = showArtistListDialog,
        artists = song.artists,
        onArtistClick = { artist ->
            globalState.setPlayerExpanded(false)
            navigator?.push(ArtistScreen(artist.id))
        },
        onDismissRequest = { showArtistListDialog = false }
    )

    PlaylistPickerDialog(
        isOpen = showPlaylistPickerDialog,
        onPlaylistSelected = { playlist ->
            playerModel.addSongsToPlaylist(playlist.id, PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
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
            playerModel.createPlaylist(name, PlaybackQueue(items = listOf(QueueEntry.Explicit(song))))
        },
        onDismissRequest = { showCreatePlaylistDialog = false }
    )

    SimilarSongsDialog(
        isOpen = showSimilarSongsDialog,
        seed = SimilarSongsSeed.Songs(listOf(song.id), song.title),
        onConfirm = { criterion, limit ->
            navigator?.push(SimilarSongsScreen(SimilarSongsSeed.Songs(listOf(song.id), song.title), criterion, limit))
        },
        onDismissRequest = { showSimilarSongsDialog = false }
    )
}
