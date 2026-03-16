package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.SongTag
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.SynaraFab
import dev.dertyp.synara.viewmodels.AllSongsScreenModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class AllSongsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<AllSongsScreenModel>()
        val state by screenModel.state.collectAsState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                        title = {
                            Text(stringResource(Res.string.songs))
                        },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                            }
                        }
                    )
                    
                    if (state is AllSongsScreenModel.AllSongsState.Success) {
                        val successState = state as AllSongsScreenModel.AllSongsState.Success
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(SongTag.entries) { tag ->
                                    FilterChip(
                                        selected = successState.tags.contains(tag),
                                        onClick = { screenModel.toggleTag(tag) },
                                        elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (tag) {
                                                        SongTag.Q_44_48 -> Res.string.tag_q_44_48
                                                        SongTag.Q_96 -> Res.string.tag_q_96
                                                        SongTag.Q_192 -> Res.string.tag_q_192
                                                        SongTag.B_16 -> Res.string.tag_b_16
                                                        SongTag.B_24 -> Res.string.tag_b_24
                                                        SongTag.HAS_LYRICS -> Res.string.tag_has_lyrics
                                                        SongTag.CUSTOM_UPLOAD -> Res.string.tag_custom_upload
                                                        SongTag.HAS_MUSICBRAINZ_ID -> Res.string.tag_has_musicbrainz_id
                                                    }
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            IconButton(
                                onClick = { screenModel.setInvertTags(!successState.invertTags) },
                                colors = if (successState.invertTags) {
                                    IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    IconButtonDefaults.iconButtonColors()
                                }
                            ) {
                                Icon(
                                    if (successState.invertTags) SynaraIcons.FilterOff.get() else SynaraIcons.Filter.get(),
                                    contentDescription = "Invert Tags"
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (state is AllSongsScreenModel.AllSongsState.Success) {
                    SynaraFab(onClick = { screenModel.playAll() }) {
                        Icon(SynaraIcons.Play.get(), contentDescription = stringResource(Res.string.play_all))
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val currentState = state) {
                    is AllSongsScreenModel.AllSongsState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is AllSongsScreenModel.AllSongsState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    }
                    is AllSongsScreenModel.AllSongsState.Success -> {
                        SongsList(
                            songs = currentState.songs,
                            screenModel = screenModel
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SongsList(
        songs: List<UserSong?>,
        screenModel: AllSongsScreenModel
    ) {
        val currentSong by screenModel.playerModel.currentSong.collectAsState()
        val lazyListState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState
            ) {
                itemsIndexed(songs, key = { index, song -> song?.id ?: "placeholder_$index" }) { index, song ->
                    if (song == null) {
                        LaunchedEffect(index) {
                            delay(200)
                            screenModel.loadPage(index / screenModel.pageSize)
                        }
                        SongPlaceholder()
                    } else {
                        SongItem(
                            song = song,
                            isCurrent = currentSong?.id == song.id,
                            showCover = true,
                            showLike = true,
                            onClick = { screenModel.playSong(song, index) },
                            onPlayNext = { screenModel.playerModel.playNext(song) },
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
    }

    @Composable
    private fun SongPlaceholder() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        SynaraIcons.Songs.get(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.shapes.extraSmall
                        )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.2f)
                        .height(12.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.shapes.extraSmall
                        )
                )
            }
        }
    }
}
