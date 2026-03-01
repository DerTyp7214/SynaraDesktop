package dev.dertyp.synara.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import dev.dertyp.data.ServerStats
import dev.dertyp.data.UserPlaylist
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.components.PlayerBar
import dev.dertyp.synara.viewmodels.HomeScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class HomeScreen : Screen {
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<HomeScreenModel>()
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
                    PlaylistNavItem(playlist) {
                        val current = navigator.lastItem
                        if (current !is PlaylistScreen || current.playlistId != playlist.id) {
                            navigator.push(PlaylistScreen(playlist.id, isUserPlaylist = true))
                        }
                        onItemClick?.invoke()
                    }
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
    private fun PlaylistNavItem(playlist: UserPlaylist, onClick: () -> Unit) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Text(
                text = playlist.name,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
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

                var searchQuery by remember { mutableStateOf("") }

                InternalTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { 
                        Text(
                            stringResource(Res.string.search), 
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
                    Icon(Icons.Rounded.CastConnected, contentDescription = stringResource(Res.string.sessions))
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
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<HomeScreenModel>()
        val stats by screenModel.serverStats.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(Res.string.dashboard),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            stats?.let { DashboardStats(it) } ?: CircularProgressIndicator()
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

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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
    private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
        ElevatedCard(
            modifier = modifier,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
