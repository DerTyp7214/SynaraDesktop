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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.ArtistTopSongOrderChips
import dev.dertyp.synara.ui.components.RegisterRefreshTarget
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.playsText
import dev.dertyp.synara.viewmodels.ArtistTopSongsScreenModel
import dev.dertyp.synara.viewmodels.ArtistTopSongsState
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

class ArtistTopSongsScreen(
    private val artistId: PlatformUUID,
    private val initialOrder: ArtistTopSongOrder = ArtistTopSongOrder.GLOBAL_LISTENS
) : Screen {

    override val key: ScreenKey = "ArtistTopSongsScreen_$artistId"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ArtistTopSongsScreenModel> { parametersOf(artistId, initialOrder) }
        RegisterRefreshTarget(screenModel)
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current

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
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TopSongsHeader(state = state, screenModel = screenModel)

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val error = state.error
                    when {
                        error != null -> {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
                            )
                        }
                        state.isLoading && state.songs.isEmpty() -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        state.songs.isEmpty() -> {
                            Text(
                                text = stringResource(Res.string.artist_top_songs_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
                            )
                        }
                        else -> TopSongsList(state = state, screenModel = screenModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun TopSongsHeader(
        state: ArtistTopSongsState,
        screenModel: ArtistTopSongsScreenModel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { screenModel.play() },
                enabled = state.songs.isNotEmpty(),
                modifier = Modifier.testTag("artist_top_songs_play")
            ) {
                Icon(SynaraIcons.Play.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.play_all))
            }
            OutlinedButton(
                onClick = { screenModel.shuffle() },
                enabled = state.songs.isNotEmpty(),
                modifier = Modifier.testTag("artist_top_songs_shuffle")
            ) {
                Icon(SynaraIcons.Shuffle.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.shuffle))
            }
            Spacer(modifier = Modifier.weight(1f))
            ArtistTopSongOrderChips(
                order = state.order,
                onOrderChange = { screenModel.setOrder(it) }
            )
        }
    }

    @Composable
    private fun TopSongsList(
        state: ArtistTopSongsState,
        screenModel: ArtistTopSongsScreenModel
    ) {
        val currentSong by screenModel.playerModel.currentSong.collectAsState()
        val lazyListState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(state.songs) { index, topSong ->
                    SongItem(
                        song = topSong.song,
                        index = index + 1,
                        isCurrent = currentSong?.id == topSong.song.id,
                        showCover = true,
                        onClick = { screenModel.play(index) },
                        onPlayNext = { screenModel.playerModel.playNext(topSong.song) },
                        supportingText = topSong.playsText(state.songsOrder)
                    )
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
