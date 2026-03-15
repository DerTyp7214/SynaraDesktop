package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.AlbumItem
import dev.dertyp.synara.ui.components.ArtistItem
import dev.dertyp.synara.ui.components.PlaylistItem
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.viewmodels.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
abstract class BaseSearchResultScreen<T, VM : BaseSearchViewModel<T>>(
    protected val query: String,
    private val titleRes: org.jetbrains.compose.resources.StringResource
) : Screen {
    @Composable
    override fun Content() {
        val screenModel = getVM(query)
        val items by screenModel.items.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val hasNextPage by screenModel.hasNextPage.collectAsState()
        val navigator = LocalNavigator.current

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(stringResource(titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                ResultList(items, hasNextPage, isLoading) { screenModel.loadMore() }
            }
        }
    }

    @Composable
    protected abstract fun getVM(query: String): VM

    @Composable
    protected abstract fun ResultList(
        items: List<T>,
        hasNextPage: Boolean,
        isLoading: Boolean,
        onLoadMore: () -> Unit
    )
}

class SearchSongsScreen(query: String) : BaseSearchResultScreen<dev.dertyp.data.UserSong, SearchSongsViewModel>(query, Res.string.songs) {
    @Composable
    override fun getVM(query: String) = getScreenModel<SearchSongsViewModel> { parametersOf(query) }

    @Composable
    override fun ResultList(items: List<dev.dertyp.data.UserSong>, hasNextPage: Boolean, isLoading: Boolean, onLoadMore: () -> Unit) {
        val playerModel = koinInject<PlayerModel>()
        val lazyListState = rememberLazyListState()
        val shouldLoadNextPage by remember {
            derivedStateOf {
                val layoutInfo = lazyListState.layoutInfo
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (layoutInfo.totalItemsCount - 5)
            }
        }
        LaunchedEffect(shouldLoadNextPage) { if (shouldLoadNextPage) onLoadMore() }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(items) { index, song ->
                    SongItem(song = song, index = index + 1, showCover = true, onClick = { playerModel.playSong(song) })
                }
                if (hasNextPage) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(lazyListState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

class SearchArtistsScreen(query: String) : BaseSearchResultScreen<dev.dertyp.data.Artist, SearchArtistsViewModel>(query, Res.string.artists) {
    @Composable
    override fun getVM(query: String) = getScreenModel<SearchArtistsViewModel> { parametersOf(query) }

    @Composable
    override fun ResultList(items: List<dev.dertyp.data.Artist>, hasNextPage: Boolean, isLoading: Boolean, onLoadMore: () -> Unit) {
        val navigator = LocalNavigator.current
        val lazyGridState = rememberLazyGridState()
        val shouldLoadNextPage by remember {
            derivedStateOf {
                val layoutInfo = lazyGridState.layoutInfo
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (layoutInfo.totalItemsCount - 5)
            }
        }
        LaunchedEffect(shouldLoadNextPage) { if (shouldLoadNextPage) onLoadMore() }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = lazyGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items) { artist ->
                    ArtistItem(artist, modifier = Modifier.fillMaxWidth()) { navigator?.push(ArtistScreen(artist.id)) }
                }
                if (hasNextPage) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
                }
            }
        }
    }
}

class SearchAlbumsScreen(query: String) : BaseSearchResultScreen<dev.dertyp.data.Album, SearchAlbumsViewModel>(query, Res.string.albums) {
    @Composable
    override fun getVM(query: String) = getScreenModel<SearchAlbumsViewModel> { parametersOf(query) }

    @Composable
    override fun ResultList(items: List<dev.dertyp.data.Album>, hasNextPage: Boolean, isLoading: Boolean, onLoadMore: () -> Unit) {
        val navigator = LocalNavigator.current
        val lazyGridState = rememberLazyGridState()
        val shouldLoadNextPage by remember {
            derivedStateOf {
                val layoutInfo = lazyGridState.layoutInfo
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (layoutInfo.totalItemsCount - 5)
            }
        }
        LaunchedEffect(shouldLoadNextPage) { if (shouldLoadNextPage) onLoadMore() }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                state = lazyGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items) { album ->
                    AlbumItem(album, horizontal = false, onClick = { navigator?.push(AlbumScreen(album.id)) })
                }
                if (hasNextPage) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
                }
            }
        }
    }
}

class SearchPlaylistsScreen(query: String) : BaseSearchResultScreen<dev.dertyp.data.UserPlaylist, SearchPlaylistsViewModel>(query, Res.string.playlists) {
    @Composable
    override fun getVM(query: String) = getScreenModel<SearchPlaylistsViewModel> { parametersOf(query) }

    @Composable
    override fun ResultList(items: List<dev.dertyp.data.UserPlaylist>, hasNextPage: Boolean, isLoading: Boolean, onLoadMore: () -> Unit) {
        val navigator = LocalNavigator.current
        val lazyGridState = rememberLazyGridState()
        val shouldLoadNextPage by remember {
            derivedStateOf {
                val layoutInfo = lazyGridState.layoutInfo
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                lastVisibleItemIndex > (layoutInfo.totalItemsCount - 5)
            }
        }
        LaunchedEffect(shouldLoadNextPage) { if (shouldLoadNextPage) onLoadMore() }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                state = lazyGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items) { playlist ->
                    PlaylistItem(playlist, modifier = Modifier.fillMaxWidth(), horizontal = false) { navigator?.push(PlaylistScreen(playlist.id, isUserPlaylist = true)) }
                }
                if (hasNextPage) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } }
                }
            }
        }
    }
}
