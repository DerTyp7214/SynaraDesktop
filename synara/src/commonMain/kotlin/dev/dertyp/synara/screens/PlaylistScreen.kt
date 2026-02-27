package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.viewmodels.PlaylistScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.play_all
import synara.synara.generated.resources.playlist

data class PlaylistScreen(val playlistId: PlatformUUID, val isUserPlaylist: Boolean) : Screen {

    override val key: ScreenKey = "PlaylistScreen_$playlistId"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<PlaylistScreenModel> { parametersOf(playlistId, isUserPlaylist) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
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
            },
            floatingActionButton = {
                if (state is PlaylistScreenModel.PlaylistState.Success) {
                    FloatingActionButton(onClick = { screenModel.playPlaylist() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(Res.string.play_all))
                    }
                }
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
                        PlaylistSongList(
                            songs = currentState.songs,
                            screenModel = screenModel
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistSongList(
        songs: List<UserSong>,
        screenModel: PlaylistScreenModel
    ) {
        val currentSong by screenModel.playerModel.currentSong.collectAsState()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(songs) { song ->
                SongItem(
                    song = song,
                    isCurrent = currentSong?.id == song.id,
                    showCover = true,
                    onClick = { screenModel.playSong(song) },
                    onPlayNext = { screenModel.playerModel.playNext(song) },
                    onMoreOptions = { /* TODO */ }
                )
            }
        }
    }
}
