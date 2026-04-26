@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlaybackQueue
import dev.dertyp.synara.player.QueueEntry
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.dialogs.CreatePlaylistDialog
import dev.dertyp.synara.ui.components.dialogs.DiscoveryCriterion
import dev.dertyp.synara.ui.components.dialogs.SimilarSongsSeed
import dev.dertyp.synara.viewmodels.SimilarSongsScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.create_playlist
import synara.synara.generated.resources.discovery_seed_songs
import synara.synara.generated.resources.no_results
import synara.synara.generated.resources.show_less
import synara.synara.generated.resources.show_more
import synara.synara.generated.resources.similar_songs_title

data class SimilarSongsScreen(
    val seed: SimilarSongsSeed,
    val criterion: DiscoveryCriterion,
    val limit: Int
) : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<SimilarSongsScreenModel> { parametersOf(seed, criterion, limit) }
        val state by screenModel.state.collectAsState()
        
        var showCreatePlaylistDialog by remember { mutableStateOf(false) }
        var seedExpanded by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = {
                        Text(stringResource(Res.string.similar_songs_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        if (state.songs.isNotEmpty()) {
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(SynaraIcons.Add.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.create_playlist))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                } else if (state.songs.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_results),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val currentSong by screenModel.playerModel.currentSong.collectAsState()
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item {
                            SeedSongsSection(
                                seedSongs = state.seedSongs,
                                expanded = seedExpanded,
                                onToggleExpand = { seedExpanded = !seedExpanded },
                                playerModel = screenModel.playerModel
                            )
                        }

                        items(state.songs) { song ->
                            SongItem(
                                song = song,
                                isCurrent = currentSong?.id == song.id,
                                showCover = true,
                                onClick = {
                                    val index = state.songs.indexOf(song)
                                    screenModel.playerModel.playQueue(
                                        PlaybackQueue(items = state.songs.map { QueueEntry.Explicit(it) }),
                                        startIndex = if (index != -1) index else 0
                                    )
                                },
                                onPlayNext = { screenModel.playerModel.playNext(song) }
                            )
                        }
                    }
                }
            }
        }

        CreatePlaylistDialog(
            isOpen = showCreatePlaylistDialog,
            onConfirm = { name ->
                screenModel.createPlaylist(name)
            },
            onDismissRequest = { showCreatePlaylistDialog = false }
        )
    }

    @Composable
    private fun SeedSongsSection(
        seedSongs: List<UserSong>,
        expanded: Boolean,
        onToggleExpand: () -> Unit,
        playerModel: dev.dertyp.synara.player.PlayerModel
    ) {
        if (seedSongs.isEmpty()) return

        val rotation by animateFloatAsState(if (expanded) 180f else 0f)

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).animateContentSize()) {
            Text(
                text = stringResource(Res.string.discovery_seed_songs),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val displaySongs = if (expanded || seedSongs.size <= 5) {
                seedSongs
            } else {
                seedSongs.take(5)
            }

            displaySongs.forEach { song ->
                SongItem(
                    song = song,
                    showCover = true,
                    onClick = { playerModel.playSong(song) },
                    onPlayNext = { playerModel.playNext(song) }
                )
            }

            if (seedSongs.size > 5) {
                TextButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (expanded) stringResource(Res.string.show_less)
                        else stringResource(Res.string.show_more)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        SynaraIcons.ExpandDown.get(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }
                    )
                }
            }
        }
    }
}
