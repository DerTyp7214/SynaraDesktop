@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.core.parseVersions
import dev.dertyp.data.Artist
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.AlbumItem
import dev.dertyp.synara.ui.components.GenresText
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.FullscreenImageDialog
import dev.dertyp.synara.ui.components.menus.ArtistContextMenu
import dev.dertyp.synara.viewmodels.ArtistScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

class ArtistScreen(private val artistId: PlatformUUID) : Screen {

    override val key: ScreenKey = "ArtistScreen_$artistId"

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ArtistScreenModel> { parametersOf(artistId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current
        val lazyListState = rememberLazyListState()

        var showFullscreenImage by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = { Text(state.artist?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = null)
                        }
                    },
                    actions = {
                        state.artist?.let { artist ->
                            Box {
                                var showArtistMenu by remember { mutableStateOf(false) }

                                IconButton(onClick = { showArtistMenu = true }) {
                                    Icon(SynaraIcons.MoreOptions.get(), contentDescription = null)
                                }

                                ArtistContextMenu(
                                    artist = artist,
                                    expanded = showArtistMenu,
                                    onDismissRequest = { showArtistMenu = false }
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            if (state.isLoading && state.artist == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                state.artist?.let { artist ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            item {
                                ArtistHeader(
                                    artist = artist,
                                    onImageClick = { showFullscreenImage = true },
                                    screenModel = screenModel
                                )
                            }

                            if (artist.about.isNotBlank()) {
                                item {
                                    ExpandableBio(
                                        text = artist.about,
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    )
                                }
                            }

                            if (state.topLikedSongs.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SectionHeader(stringResource(Res.string.top_liked_songs))
                                        TextButton(onClick = { navigator?.push(ArtistLikedSongsScreen(artistId)) }) {
                                            Text(stringResource(Res.string.show_all))
                                        }
                                    }
                                }

                                itemsIndexed(state.topLikedSongs) { index, song ->
                                    val currentSong by screenModel.playerModel.currentSong.collectAsState()
                                    SongItem(
                                        song = song,
                                        index = index + 1,
                                        isCurrent = currentSong?.id == song.id,
                                        showCover = true,
                                        onClick = { screenModel.playSong(song) },
                                        onPlayNext = { screenModel.playNext(song) }
                                    )
                                }
                            }

                            if (state.topSongs.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SectionHeader(stringResource(Res.string.songs))
                                        TextButton(onClick = { navigator?.push(ArtistSongsScreen(artistId)) }) {
                                            Text(stringResource(Res.string.show_all))
                                        }
                                    }
                                }

                                itemsIndexed(state.topSongs) { index, song ->
                                    val currentSong by screenModel.playerModel.currentSong.collectAsState()
                                    SongItem(
                                        song = song,
                                        index = index + 1,
                                        isCurrent = currentSong?.id == song.id,
                                        showCover = true,
                                        onClick = { screenModel.playSong(song) },
                                        onPlayNext = { screenModel.playNext(song) }
                                    )
                                }
                            }

                            if (state.albums.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SectionHeader(stringResource(Res.string.albums))
                                        TextButton(onClick = { navigator?.push(ArtistAlbumsScreen(artistId)) }) {
                                            Text(stringResource(Res.string.show_all))
                                        }
                                    }
                                }

                                val grouped = state.albums.groupBy { it.coverId }.values.toList()
                                items(grouped.take(10)) { albumVersions ->
                                    val (album, versions) = albumVersions.parseVersions()
                                    AlbumItem(
                                        album = album,
                                        modifier = Modifier.fillMaxWidth(),
                                        subText = if (versions.size > 1) {
                                            stringResource(Res.string.versions_count, versions.size)
                                        } else {
                                            null
                                        },
                                        onClick = { navigator?.push(AlbumScreen(album.id)) }
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

                    FullscreenImageDialog(
                        isOpen = showFullscreenImage,
                        imageId = artist.imageId,
                        onDismissRequest = { showFullscreenImage = false }
                    )
                }
            }
        }
    }

    @Composable
    private fun ExpandableBio(
        text: String,
        modifier: Modifier = Modifier
    ) {
        var isExpanded by remember { mutableStateOf(false) }
        var hasOverflow by remember { mutableStateOf(false) }

        Column(modifier = modifier.animateContentSize()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    hasOverflow = textLayoutResult.hasVisualOverflow
                }
            )

            if (hasOverflow || isExpanded) {
                TextButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = if (isExpanded) stringResource(Res.string.show_less) else stringResource(Res.string.show_more),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    @Composable
    private fun ArtistHeader(
        artist: Artist,
        onImageClick: () -> Unit,
        screenModel: ArtistScreenModel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SynaraImage(
                imageId = artist.imageId,
                size = 200.dp,
                shape = MaterialTheme.shapes.medium,
                onClick = { onImageClick() },
                fallbackIcon = SynaraIcons.Artists
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )

                GenresText(
                    genres = artist.genres,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { screenModel.playArtist() },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(SynaraIcons.Play.get(), contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.play))
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
