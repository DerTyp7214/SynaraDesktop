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
import dev.dertyp.PlatformUUID
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.screens.AlbumScreen
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.screens.MetadataEditScreen
import dev.dertyp.synara.screens.SimilarSongsScreen
import dev.dertyp.services.IUiService
import dev.dertyp.synara.services.DownloadStatus
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.*
import dev.dertyp.synara.ui.server.UiEntry
import dev.dertyp.synara.ui.server.UiHostOverlays
import dev.dertyp.synara.ui.server.UiIconView
import dev.dertyp.synara.ui.server.UiOpenMenuContent
import dev.dertyp.synara.ui.server.asEntry
import dev.dertyp.synara.ui.server.isServerUiAvailable
import dev.dertyp.synara.ui.server.rememberUiHost
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiEntityType
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlots
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

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
    var showCollectionPickerDialog by remember { mutableStateOf(false) }
    var showSimilarSongsDialog by remember { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val user by globalState.user.collectAsState()

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

        if (user?.hasCapability(UserCapability.EDIT) == true) {
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
        }

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
            text = { Text(stringResource(Res.string.add_to_collection)) },
            onClick = {
                showCollectionPickerDialog = true
                onDismissRequest()
            },
            leadingIcon = {
                Icon(
                    SynaraIcons.Collections.get(),
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

        SongMenuUiExtras(song = song, onDismissRequest = onDismissRequest)

        Spacer(modifier = Modifier.height(8.dp))

        if (user?.hasCapability(UserCapability.DELETE) == true) {
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

    AddToCollectionDialog(
        isOpen = showCollectionPickerDialog,
        itemType = CollectionItemType.SONG,
        itemId = song.id,
        onDismissRequest = { showCollectionPickerDialog = false }
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

private data class CachedSongMenu(val at: Long, val items: List<UiRender>)

private val songMenuCache = object : LinkedHashMap<PlatformUUID, CachedSongMenu>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlatformUUID, CachedSongMenu>?): Boolean = size > 32
}

private const val SONG_MENU_CACHE_MS = 30_000L

private suspend fun loadSongMenu(uiService: IUiService, songId: PlatformUUID): List<UiRender> {
    val now = currentTimeMillis()
    synchronized(songMenuCache) {
        songMenuCache[songId]?.takeIf { now - it.at < SONG_MENU_CACHE_MS }?.let { return it.items }
    }
    val items = try {
        uiService.renderSlot(UiSlots.SONG_MENU, UiContext(UiEntityType.SONG, songId)).items
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }
    synchronized(songMenuCache) { songMenuCache[songId] = CachedSongMenu(now, items) }
    return items
}

private fun songMenuEntries(node: UiComponent): List<UiEntry> = when (node) {
    is UiComponent.Text -> listOf(UiEntry(node.text, icon = null, action = null, enabled = false))
    is UiComponent.Column -> node.children.flatMap { songMenuEntries(it) }
    is UiComponent.Row -> node.children.flatMap { songMenuEntries(it) }
    is UiComponent.Card -> node.children.flatMap { songMenuEntries(it) }
    is UiComponent.Section -> node.children.flatMap { songMenuEntries(it) }
    else -> node.asEntry()?.takeIf { it.action != null }?.let { listOf(it) } ?: emptyList()
}

@Composable
private fun SongMenuUiExtras(
    song: UserSong,
    onDismissRequest: () -> Unit,
    uiService: IUiService = koinInject(),
) {
    if (!isServerUiAvailable()) return

    var loaded by remember(song.id) { mutableStateOf<List<UiRender>?>(null) }
    LaunchedEffect(song.id) {
        loaded = loadSongMenu(uiService, song.id)
    }
    val items = loaded ?: return

    val context = remember(song.id) { UiContext(entityType = UiEntityType.SONG, entityId = song.id) }
    val host = rememberUiHost("songMenu.${song.id}", context)
    var submenu by remember(song.id) { mutableStateOf<UiAction.OpenMenu?>(null) }
    val entries = remember(items) { items.flatMap { songMenuEntries(it.root) } }
    if (entries.isEmpty()) return

    HorizontalDivider()

    entries.forEach { entry ->
        val destructive = entry.destructive
        DropdownMenuItem(
            text = { Text(entry.title) },
            enabled = entry.enabled,
            onClick = {
                val action = entry.action
                if (action is UiAction.OpenMenu) {
                    submenu = action
                } else if (action != null) {
                    onDismissRequest()
                    host.dispatch(action)
                }
            },
            leadingIcon = entry.icon?.let { icon ->
                {
                    UiIconView(
                        icon,
                        size = 20.dp,
                        tint = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            },
            colors = if (destructive) MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            ) else MenuDefaults.itemColors()
        )
    }

    UiHostOverlays(host)

    UiOpenMenuContent(
        menu = submenu,
        host = host,
        onDismiss = { submenu = null },
        onItemDispatched = {
            submenu = null
            onDismissRequest()
        },
    )
}
