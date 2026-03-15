@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.synara.formatHumanReadableDuration
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.ArtistsText
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.AlbumVersionsDialog
import dev.dertyp.synara.ui.components.dialogs.FullscreenImageDialog
import dev.dertyp.synara.viewmodels.AlbumScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

class AlbumScreen(private val albumId: PlatformUUID) : Screen {

    override val key: ScreenKey = "AlbumScreen_$albumId"

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<AlbumScreenModel> { parametersOf(albumId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current
        val lazyListState = rememberLazyListState()

        var showVersionsDialog by remember { mutableStateOf(false) }
        var showFullscreenImage by remember { mutableStateOf(false) }

        val groupedSongs = remember(state.songs) {
            state.songs.groupBy { it.discNumber }.toSortedMap()
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = { Text(state.album?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.ArrowBack.get(), contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            state.album?.let { album ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item {
                            AlbumHeader(
                                album = album,
                                versions = state.versions,
                                totalDuration = state.totalDuration,
                                onShowVersions = { showVersionsDialog = true },
                                onImageClick = { showFullscreenImage = true },
                                screenModel = screenModel
                            )
                        }

                        groupedSongs.forEach { (discNumber, discSongs) ->
                            if (groupedSongs.size > 1) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.disc_number, discNumber),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 8.dp
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            items(discSongs) { song ->
                                val currentSong by screenModel.playerModel.currentSong.collectAsState()
                                val index = state.songs.indexOf(song)
                                SongItem(
                                    song = song,
                                    index = song.trackNumber,
                                    isCurrent = currentSong?.id == song.id,
                                    onClick = { screenModel.playAlbum(startIndex = index) },
                                    onPlayNext = { screenModel.playNext(song) }
                                )
                            }
                        }
                    }

                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(
                            scrollState = lazyListState
                        )
                    )
                }

                AlbumVersionsDialog(
                    isOpen = showVersionsDialog,
                    versions = state.versions,
                    onVersionClick = { version ->
                        navigator?.push(AlbumScreen(version.id))
                    },
                    onDismissRequest = { showVersionsDialog = false }
                )

                FullscreenImageDialog(
                    isOpen = showFullscreenImage,
                    imageId = album.coverId,
                    onDismissRequest = { showFullscreenImage = false }
                )
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isLoading) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    @Composable
    private fun AlbumHeader(
        album: Album,
        versions: List<Album>,
        totalDuration: Long,
        onShowVersions: () -> Unit,
        onImageClick: () -> Unit,
        screenModel: AlbumScreenModel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SynaraImage(
                imageId = album.coverId,
                size = 200.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickable { onImageClick() },
                fallbackIcon = SynaraIcons.Album
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                ArtistsText(
                    artists = album.artists,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "${album.releaseDate?.year ?: ""} • ${album.songCount} ${stringResource(Res.string.songs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (totalDuration > 0) {
                    Text(
                        text = totalDuration.formatHumanReadableDuration(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (versions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onShowVersions,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(SynaraIcons.Layers.get(), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.other_versions, versions.size),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screenModel.playAlbum() },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(SynaraIcons.PlayArrow.get(), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.play))
                    }

                    OutlinedButton(
                        onClick = { screenModel.addToQueue() }
                    ) {
                        Icon(SynaraIcons.Add.get(), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.add_to_queue))
                    }
                }
            }
        }
    }
}
