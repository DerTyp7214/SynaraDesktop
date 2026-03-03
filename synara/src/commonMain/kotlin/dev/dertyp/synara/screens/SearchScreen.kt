package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.components.AlbumItem
import dev.dertyp.synara.ui.components.ArtistItem
import dev.dertyp.synara.ui.components.PlaylistItem
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.fadingEdge
import dev.dertyp.synara.viewmodels.GlobalStateModel
import dev.dertyp.synara.viewmodels.SearchScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class SearchScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<SearchScreenModel>()
        val globalState = koinInject<GlobalStateModel>()
        val playerModel = koinInject<PlayerModel>()
        val navigator = LocalNavigator.currentOrThrow
        val query by globalState.searchQuery.collectAsState()
        
        val isSearching by screenModel.isSearching.collectAsState()
        val songs by screenModel.songs.collectAsState()
        val albums by screenModel.albums.collectAsState()
        val artists by screenModel.artists.collectAsState()
        val playlists by screenModel.playlists.collectAsState()

        val lazyListState = screenModel.lazyListState

        LaunchedEffect(query) {
            screenModel.search(query)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Text(
                        text = if (query.isEmpty()) stringResource(Res.string.search) else stringResource(Res.string.search_results_for, query),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isSearching) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (query.isNotEmpty() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(Res.string.no_results),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    if (songs.isNotEmpty()) {
                        item {
                            SearchSection(
                                title = stringResource(Res.string.songs),
                                onShowAll = { navigator.push(SearchSongsScreen(query)) }
                            ) {
                                Column {
                                    songs.take(5).forEach { song ->
                                        SongItem(
                                            song = song,
                                            showCover = true,
                                            onClick = {
                                                playerModel.playSong(song)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (artists.isNotEmpty()) {
                        item {
                            SearchSection(
                                title = stringResource(Res.string.artists),
                                onShowAll = { navigator.push(SearchArtistsScreen(query)) }
                            ) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fadingEdge(Orientation.Horizontal)
                                ) {
                                    items(artists) { artist ->
                                        ArtistItem(artist) {
                                            navigator.push(ArtistScreen(artist.id))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (albums.isNotEmpty()) {
                        item {
                            SearchSection(
                                title = stringResource(Res.string.albums),
                                onShowAll = { navigator.push(SearchAlbumsScreen(query)) }
                            ) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .fadingEdge(Orientation.Horizontal)
                                ) {
                                    val chunkedAlbums = albums.chunked(2)
                                    items(chunkedAlbums) { pair ->
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { album ->
                                                AlbumItem(
                                                    album = album,
                                                    horizontal = true,
                                                    modifier = Modifier.width(300.dp)
                                                ) {
                                                    navigator.push(AlbumScreen(album.id))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (playlists.isNotEmpty()) {
                        item {
                            SearchSection(
                                title = stringResource(Res.string.playlists),
                                onShowAll = { navigator.push(SearchPlaylistsScreen(query)) }
                            ) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .fadingEdge(Orientation.Horizontal)
                                ) {
                                    val chunkedPlaylists = playlists.chunked(2)
                                    items(chunkedPlaylists) { pair ->
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pair.forEach { playlist ->
                                                PlaylistItem(
                                                    playlist = playlist,
                                                    horizontal = true,
                                                    modifier = Modifier.width(300.dp)
                                                ) {
                                                    navigator.push(PlaylistScreen(playlist.id, isUserPlaylist = true))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }

    @Composable
    private fun SearchSection(
        title: String,
        onShowAll: () -> Unit,
        content: @Composable () -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onShowAll) {
                    Text(stringResource(Res.string.show_all))
                }
            }
            content()
        }
    }
}
