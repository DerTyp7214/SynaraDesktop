package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraFab
import dev.dertyp.synara.viewmodels.LikedSongsScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.favorite
import synara.synara.generated.resources.play_all

class LikedSongsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<LikedSongsScreenModel>()
        val state by screenModel.state.collectAsState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = {
                        Text(stringResource(Res.string.favorite))
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    }
                )
            },
            floatingActionButton = {
                if (state is LikedSongsScreenModel.LikedSongsState.Success) {
                    SynaraFab(onClick = { screenModel.playAll() }) {
                        Icon(SynaraIcons.Play.get(), contentDescription = stringResource(Res.string.play_all))
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val currentState = state) {
                    is LikedSongsScreenModel.LikedSongsState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is LikedSongsScreenModel.LikedSongsState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    }
                    is LikedSongsScreenModel.LikedSongsState.Success -> {
                        LikedSongsList(
                            songs = currentState.songs,
                            hasNextPage = currentState.hasNextPage,
                            screenModel = screenModel
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LikedSongsList(
        songs: List<UserSong>,
        hasNextPage: Boolean,
        screenModel: LikedSongsScreenModel
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
                state = lazyListState
            ) {
                items(songs) { song ->
                    SongItem(
                        song = song,
                        isCurrent = currentSong?.id == song.id,
                        showCover = true,
                        showLike = false,
                        onClick = { screenModel.playSong(song) },
                        onPlayNext = { screenModel.playerModel.playNext(song) },
                    )
                }

                if (hasNextPage) {
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
}
