@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlayArrow
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
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.PlatformUUID
import dev.dertyp.synara.formatHumanReadableDuration
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.FullscreenImageDialog
import dev.dertyp.synara.viewmodels.PlaylistScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

data class PlaylistScreen(val playlistId: PlatformUUID, val isUserPlaylist: Boolean) : Screen {

    override val key: ScreenKey = "PlaylistScreen_$playlistId"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<PlaylistScreenModel> { parametersOf(playlistId, isUserPlaylist) }
        val state by screenModel.state.collectAsState()

        var showFullscreenImage by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = {
                        Text(
                            when (val currentState = state) {
                                is PlaylistScreenModel.PlaylistState.Success -> currentState.name
                                else -> stringResource(Res.string.playlist)
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val currentState = state) {
                    is PlaylistScreenModel.PlaylistState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is PlaylistScreenModel.PlaylistState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    }
                    is PlaylistScreenModel.PlaylistState.Success -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            PlaylistContent(
                                state = currentState,
                                onImageClick = { showFullscreenImage = true },
                                screenModel = screenModel
                            )

                            FullscreenImageDialog(
                                isOpen = showFullscreenImage,
                                imageId = currentState.imageId,
                                onDismissRequest = { showFullscreenImage = false }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistContent(
        state: PlaylistScreenModel.PlaylistState.Success,
        onImageClick: () -> Unit,
        screenModel: PlaylistScreenModel
    ) {
        val currentSong by screenModel.playerModel.currentSong.collectAsState()
        val lazyListState = rememberLazyListState()

        val shouldLoadNextPage by remember {
            derivedStateOf {
                val layoutInfo = lazyListState.layoutInfo
                val totalItemsNumber = layoutInfo.totalItemsCount
                val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

                lastVisibleItemIndex > (totalItemsNumber - 10)
            }
        }

        LaunchedEffect(shouldLoadNextPage) {
            if (shouldLoadNextPage) {
                screenModel.loadNextPage()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    PlaylistHeader(
                        state = state,
                        onImageClick = onImageClick,
                        screenModel = screenModel
                    )
                }

                items(state.songs) { song ->
                    SongItem(
                        song = song,
                        isCurrent = currentSong?.id == song.id,
                        showCover = true,
                        onClick = { screenModel.playSong(song) },
                        onPlayNext = { screenModel.playerModel.playNext(song) },
                    )
                }

                if (state.hasNextPage) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
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
    }

    @Composable
    private fun PlaylistHeader(
        state: PlaylistScreenModel.PlaylistState.Success,
        onImageClick: () -> Unit,
        screenModel: PlaylistScreenModel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SynaraImage(
                imageId = state.imageId,
                size = 200.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickable { onImageClick() },
                fallbackIcon = Icons.AutoMirrored.Rounded.PlaylistPlay
            )

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "${state.songs.size} ${stringResource(Res.string.songs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.totalDuration > 0) {
                    Text(
                        text = state.totalDuration.formatHumanReadableDuration(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screenModel.playPlaylist() },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.play))
                    }
                }
            }
        }
    }
}
