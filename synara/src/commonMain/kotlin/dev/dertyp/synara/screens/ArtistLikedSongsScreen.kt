package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.viewmodels.ArtistLikedSongsScreenModel
import org.koin.core.parameter.parametersOf

class ArtistLikedSongsScreen(private val artistId: PlatformUUID) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ArtistLikedSongsScreenModel> { parametersOf(artistId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = {
                        Text(
                            when (val s = state) {
                                is ArtistLikedSongsScreenModel.ArtistLikedSongsState.Success -> s.artist?.name ?: ""
                                is ArtistLikedSongsScreenModel.ArtistLikedSongsState.Loading -> s.artist?.name ?: ""
                                else -> ""
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.ArrowBack.get(), contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val currentState = state) {
                    is ArtistLikedSongsScreenModel.ArtistLikedSongsState.Loading -> {
                        if (currentState.artist == null) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            ArtistLikedSongsList(
                                songs = emptyList(),
                                hasNextPage = true,
                                screenModel = screenModel
                            )
                        }
                    }
                    is ArtistLikedSongsScreenModel.ArtistLikedSongsState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    }
                    is ArtistLikedSongsScreenModel.ArtistLikedSongsState.Success -> {
                        ArtistLikedSongsList(
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
    private fun ArtistLikedSongsList(
        songs: List<UserSong>,
        hasNextPage: Boolean,
        screenModel: ArtistLikedSongsScreenModel
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
                screenModel.loadSongs()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    SongItem(
                        song = song,
                        index = index + 1,
                        isCurrent = currentSong?.id == song.id,
                        showCover = true,
                        onClick = { screenModel.playSong(song) },
                        onPlayNext = { screenModel.playerModel.playNext(song) }
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
