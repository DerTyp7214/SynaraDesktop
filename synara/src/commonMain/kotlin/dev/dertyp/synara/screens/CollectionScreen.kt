package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.PlatformUUID
import dev.dertyp.data.CollectionItemType
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.server.UiSlot
import dev.dertyp.synara.utils.pickImageBytes
import dev.dertyp.synara.viewmodels.CollectionScreenModel
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiSlots
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

data class CollectionScreen(val collectionId: PlatformUUID) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<CollectionScreenModel> { parametersOf(collectionId) }
        val playerModel = koinInject<PlayerModel>()
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var showEditDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        LaunchedEffect(state.isDeleted) {
            if (state.isDeleted) navigator.pop()
        }

        val lazyListState = rememberLazyListState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        val collection = state.collection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            SynaraImage(
                                imageId = collection?.imageId,
                                size = 128.dp,
                                shape = MaterialTheme.shapes.medium,
                                fallbackIcon = SynaraIcons.Collections
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = collection?.name ?: "",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                collection?.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                collection?.let {
                                    Text(
                                        text = "${it.songCount} ${stringResource(Res.string.songs)} · ${it.totalSizeBytes.formatBytes()}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val songs = state.results?.songs?.data ?: return@Button
                                            if (songs.isEmpty()) return@Button
                                            playerModel.playQueue(
                                                PlaybackQueue(items = songs.map { QueueEntry.Explicit(it.song) })
                                            )
                                        },
                                        enabled = !state.results?.songs?.data.isNullOrEmpty()
                                    ) {
                                        Icon(
                                            SynaraIcons.Play.get(),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(Res.string.play_all))
                                    }
                                    OutlinedButton(onClick = { showEditDialog = true }) {
                                        Text(stringResource(Res.string.collection_edit))
                                    }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            val bytes = pickImageBytes() ?: return@launch
                                            screenModel.setCover(bytes)
                                        }
                                    }) {
                                        Text(stringResource(Res.string.choose_image))
                                    }
                                    IconButton(onClick = { showDeleteDialog = true }) {
                                        Icon(
                                            SynaraIcons.Delete.get(),
                                            contentDescription = stringResource(Res.string.delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        InternalTextField(
                            value = state.query,
                            onValueChange = { screenModel.setQuery(it) },
                            label = { Text(stringResource(Res.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val results = state.results
                    if (state.isLoading && results == null) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (results != null) {
                        val songs = results.songs.data
                        val artists = results.artists.data
                        val albums = results.albums.data
                        val playlists = results.playlists.data

                        if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.collection_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (artists.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.artists),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(artists.size, key = { "artist_${artists[it].id}" }) { index ->
                                val artist = artists[index]
                                CollectionItemRow(
                                    imageId = artist.imageId,
                                    fallbackIcon = SynaraIcons.Artists,
                                    imageShape = CircleShape,
                                    title = artist.name,
                                    onClick = { navigator.push(ArtistScreen(artist.id)) },
                                    onRemove = { screenModel.removeItem(CollectionItemType.ARTIST, artist.id) }
                                )
                            }
                        }

                        if (albums.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.albums),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(albums.size, key = { "album_${albums[it].id}" }) { index ->
                                val album = albums[index]
                                CollectionItemRow(
                                    imageId = album.coverId,
                                    fallbackIcon = SynaraIcons.Albums,
                                    title = album.name,
                                    onClick = { navigator.push(AlbumScreen(album.id)) },
                                    onRemove = { screenModel.removeItem(CollectionItemType.ALBUM, album.id) }
                                )
                            }
                        }

                        if (playlists.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.playlists),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(playlists.size, key = { "playlist_${playlists[it].id}" }) { index ->
                                val playlist = playlists[index]
                                CollectionItemRow(
                                    imageId = playlist.imageId,
                                    fallbackIcon = SynaraIcons.PlayNext,
                                    title = playlist.name,
                                    onClick = { navigator.push(PlaylistScreen(playlist.id, isUserPlaylist = true)) },
                                    onRemove = { screenModel.removeItem(CollectionItemType.PLAYLIST, playlist.id) }
                                )
                            }
                        }

                        if (songs.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.songs),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(songs.size, key = { "song_${songs[it].song.id}" }) { index ->
                                val match = songs[index]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        SongItem(
                                            song = match.song,
                                            showCover = true,
                                            onClick = {
                                                playerModel.playQueue(
                                                    PlaybackQueue(items = songs.map { QueueEntry.Explicit(it.song) }),
                                                    startIndex = index
                                                )
                                            }
                                        )
                                    }
                                    if (match.explicitMember) {
                                        IconButton(onClick = {
                                            screenModel.removeItem(CollectionItemType.SONG, match.song.id)
                                        }) {
                                            Icon(
                                                SynaraIcons.Close.get(),
                                                contentDescription = stringResource(Res.string.remove_from_collection)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        UiSlot(
                            slot = UiSlots.COLLECTION_DETAIL,
                            context = UiContext(entityId = collectionId),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        if (showEditDialog) {
            CollectionEditDialog(
                title = stringResource(Res.string.collection_edit),
                initialName = state.collection?.name ?: "",
                initialDescription = state.collection?.description ?: "",
                onConfirm = { name, description ->
                    screenModel.updateCollection(name, description)
                    showEditDialog = false
                },
                onDismissRequest = { showEditDialog = false }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(state.collection?.name ?: "") },
                text = { Text(stringResource(Res.string.collection_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        screenModel.delete()
                    }) {
                        Text(
                            stringResource(Res.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun CollectionItemRow(
        imageId: PlatformUUID?,
        fallbackIcon: SynaraIcons,
        title: String,
        onClick: () -> Unit,
        onRemove: () -> Unit,
        imageShape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SynaraImage(
                imageId = imageId,
                size = 44.dp,
                shape = imageShape,
                fallbackIcon = fallbackIcon
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRemove) {
                Icon(
                    SynaraIcons.Close.get(),
                    contentDescription = stringResource(Res.string.remove_from_collection)
                )
            }
        }
    }
}
