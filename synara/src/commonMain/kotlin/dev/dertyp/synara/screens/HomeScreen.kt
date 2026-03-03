package dev.dertyp.synara.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.transitions.SlideTransition
import dev.dertyp.data.ServerStats
import dev.dertyp.data.UserPlaylist
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.components.*
import dev.dertyp.synara.ui.models.AnnotatedSnackbarVisuals
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.HomeScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class HomeScreen : Screen {
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<HomeScreenModel>()
        val snackbarManager = koinInject<SnackbarManager>()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val isPlayerExpanded by screenModel.globalState.isPlayerExpanded.collectAsState()

        Navigator(DashboardScreen()) { navigator ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompact = maxWidth < 900.dp
                val playerHeight = 110.dp

                val backInputModifier = Modifier.pointerInput(navigator, isPlayerExpanded) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && (event.buttons.isBackPressed || event.button?.index == 5)) {
                                if (isPlayerExpanded) {
                                    screenModel.globalState.setPlayerExpanded(false)
                                } else if (navigator.canPop) {
                                    navigator.pop()
                                }
                            }
                        }
                    }
                }

                if (isCompact) {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(
                                modifier = Modifier.width(300.dp),
                                drawerContainerColor = MaterialTheme.colorScheme.surface,
                                drawerContentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                SidebarContent(screenModel, navigator, onItemClick = {
                                    scope.launch { drawerState.close() }
                                })
                            }
                        },
                        gesturesEnabled = !isPlayerExpanded
                    ) {
                        Box(modifier = Modifier.fillMaxSize().then(backInputModifier)) {
                            Column(modifier = Modifier.fillMaxSize().padding(bottom = playerHeight)) {
                                TopBar(screenModel, navigator, onMenuClick = {
                                    scope.launch { drawerState.open() }
                                }, showMenu = true)

                                Box(modifier = Modifier.fillMaxSize()) {
                                    SlideTransition(navigator)
                                }
                            }
                            
                            SnackbarHost(
                                hostState = snackbarManager.snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = playerHeight + 16.dp, end = 16.dp)
                            ) { data ->
                                val visuals = data.visuals
                                if (visuals is AnnotatedSnackbarVisuals) {
                                    Snackbar(
                                        modifier = Modifier.padding(12.dp),
                                        action = data.visuals.actionLabel?.let {
                                            {
                                                TextButton(onClick = { data.performAction() }) {
                                                    Text(it)
                                                }
                                            }
                                        }
                                    ) {
                                        Text(visuals.annotatedMessage)
                                    }
                                } else {
                                    Snackbar(data)
                                }
                            }

                            PlayerBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                height = playerHeight
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().then(backInputModifier)) {
                        Row(modifier = Modifier.fillMaxSize().padding(bottom = playerHeight)) {
                            Sidebar(screenModel, navigator)

                            Column(modifier = Modifier.weight(1f)) {
                                TopBar(screenModel, navigator)

                                Box(modifier = Modifier.fillMaxSize()) {
                                    SlideTransition(navigator)
                                }
                            }
                        }

                        SnackbarHost(
                            hostState = snackbarManager.snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = playerHeight + 16.dp, end = 16.dp)
                        ) { data ->
                            val visuals = data.visuals
                            if (visuals is AnnotatedSnackbarVisuals) {
                                Snackbar(
                                    modifier = Modifier.padding(12.dp),
                                    action = data.visuals.actionLabel?.let {
                                        {
                                            TextButton(onClick = { data.performAction() }) {
                                                Text(it)
                                            }
                                        }
                                    }
                                ) {
                                    Text(visuals.annotatedMessage)
                                }
                            } else {
                                Snackbar(data)
                            }
                        }

                        PlayerBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            height = playerHeight
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Sidebar(screenModel: HomeScreenModel, navigator: Navigator) {
        Surface(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 1.dp
        ) {
            SidebarContent(screenModel, navigator)
        }
    }

    @Composable
    private fun SidebarContent(
        screenModel: HomeScreenModel,
        navigator: Navigator,
        onItemClick: (() -> Unit)? = null
    ) {
        val playlists by screenModel.globalState.userPlaylists.collectAsState()
        val isRefreshing by screenModel.globalState.isRefreshingPlaylists.collectAsState()

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.library),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
            )

            NavigationItem(
                label = stringResource(Res.string.home),
                icon = Icons.Rounded.Home,
                selected = navigator.lastItem is DashboardScreen,
                onClick = {
                    if (navigator.lastItem !is DashboardScreen) navigator.replaceAll(DashboardScreen())
                    onItemClick?.invoke()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            NavigationItem(
                label = stringResource(Res.string.favorite),
                icon = Icons.Rounded.Favorite,
                selected = navigator.lastItem is LikedSongsScreen,
                onClick = {
                    if (navigator.lastItem !is LikedSongsScreen) navigator.push(LikedSongsScreen())
                    onItemClick?.invoke()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, start = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.playlists),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = { screenModel.globalState.refreshPlaylists() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.refresh_playlists),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(playlists) { playlist ->
                    val current = navigator.lastItem
                    val isSelected = current is PlaylistScreen && current.playlistId == playlist.id

                    PlaylistNavItem(
                        playlist = playlist,
                        selected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                navigator.push(PlaylistScreen(playlist.id, isUserPlaylist = true))
                            }
                            onItemClick?.invoke()
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun NavigationItem(
        label: String,
        icon: ImageVector,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun PlaylistNavItem(
        playlist: UserPlaylist, 
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SynaraImage(
                    imageId = playlist.imageId,
                    size = 32.dp,
                    shape = RoundedCornerShape(4.dp),
                    fallbackIcon = Icons.AutoMirrored.Rounded.PlaylistPlay
                )
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun TopBar(
        screenModel: HomeScreenModel, 
        navigator: Navigator, 
        onMenuClick: (() -> Unit)? = null, 
        showMenu: Boolean = false
    ) {
        val isDark = isAppDark()
        val searchQuery by screenModel.globalState.searchQuery.collectAsState()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            tonalElevation = 2.dp,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showMenu) {
                    IconButton(onClick = { onMenuClick?.invoke() }) {
                        Icon(Icons.Rounded.Menu, contentDescription = null)
                    }
                }

                InternalTextField(
                    value = searchQuery,
                    onValueChange = { 
                        screenModel.globalState.setSearchQuery(it)
                        if (it.isNotEmpty() && navigator.lastItem !is SearchScreen) {
                            navigator.push(SearchScreen())
                        }
                    },
                    placeholder = { 
                        Text(
                            stringResource(Res.string.search_hint), 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    leadingIcon = { 
                        Icon(
                            Icons.Rounded.Search, 
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { screenModel.globalState.setSearchQuery("") }) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                )

                IconButton(onClick = { screenModel.toggleDarkMode() }) {
                    Icon(
                        imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = stringResource(Res.string.dark_mode)
                    )
                }

                IconButton(onClick = { 
                    if (navigator.lastItem !is SessionsScreen) navigator.push(SessionsScreen())
                }) {
                    Icon(Icons.Rounded.Devices, contentDescription = stringResource(Res.string.sessions))
                }

                IconButton(onClick = { 
                    if (navigator.lastItem !is SettingsScreen) navigator.push(SettingsScreen())
                }) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(Res.string.settings))
                }
            }
        }
    }
}

private class DashboardScreen : Screen {
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<HomeScreenModel>()
        val playerModel = koinInject<PlayerModel>()
        val navigator = LocalNavigator.currentOrThrow
        val stats by screenModel.serverStats.collectAsState()
        val recentSongs by screenModel.recentSongs.collectAsState(emptyList())
        val recentAlbums by screenModel.recentAlbums.collectAsState(emptyList())
        val recentArtists by screenModel.recentArtists.collectAsState(emptyList())

        val lazyListState = rememberLazyListState()
        val albumsLazyListState = rememberLazyListState()
        val artistsLazyListState = rememberLazyListState()

        LaunchedEffect(recentAlbums) {
            if (albumsLazyListState.firstVisibleItemIndex <= 1) {
                albumsLazyListState.animateScrollToItem(0)
            }
        }

        LaunchedEffect(recentArtists) {
            if (artistsLazyListState.firstVisibleItemIndex <= 1) {
                artistsLazyListState.animateScrollToItem(0)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(Res.string.dashboard),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }

                if (recentAlbums.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        RecentlyPlayedSection(
                            title = stringResource(Res.string.recently_played_albums),
                        ) {
                            LazyRow(
                                state = albumsLazyListState,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(recentAlbums, key = { it.id }) { album ->
                                    Box(modifier = Modifier.animateItem()) {
                                        AlbumItem(
                                            album = album,
                                            horizontal = false,
                                            modifier = Modifier.width(160.dp),
                                            onClick = { navigator.push(AlbumScreen(album.id)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (recentArtists.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        RecentlyPlayedSection(
                            title = stringResource(Res.string.recently_played_artists),
                        ) {
                            LazyRow(
                                state = artistsLazyListState,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(recentArtists, key = { it.id }) { artist ->
                                    Box(modifier = Modifier.animateItem()) {
                                        ArtistItem(
                                            artist = artist,
                                            modifier = Modifier.width(140.dp),
                                            onClick = { navigator.push(ArtistScreen(artist.id)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (recentSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.recently_played_songs),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    items(recentSongs, key = { it.id }) { song ->
                        Box(modifier = Modifier.animateItem()) {
                            SongItem(
                                song = song,
                                showCover = true,
                                onClick = { playerModel.playSong(song) }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    AnimatedContent(
                        targetState = stats,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        }
                    ) { targetStats ->
                        if (targetStats != null) {
                            DashboardStats(targetStats)
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(lazyListState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 4.dp, top = 24.dp, bottom = 24.dp)
            )
        }
    }

    @Composable
    private fun RecentlyPlayedSection(
        title: String,
        content: @Composable () -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }

    @Composable
    private fun DashboardStats(stats: ServerStats) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCard(stringResource(Res.string.songs), stats.songCount.toString(), Modifier.weight(1f))
                StatCard(stringResource(Res.string.albums), stats.albumCount.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatCard(stringResource(Res.string.artists), stats.artistCount.toString(), Modifier.weight(1f))
                StatCard(stringResource(Res.string.playlists), stats.playlistCount.toString(), Modifier.weight(1f))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(Res.string.server_version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stats.version.version,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
