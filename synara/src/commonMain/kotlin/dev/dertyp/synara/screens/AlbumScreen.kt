@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import dev.dertyp.PlatformUUID
import dev.dertyp.core.joinArtists
import dev.dertyp.data.Album
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.ui.components.rememberImageRequest
import dev.dertyp.synara.viewmodels.AlbumScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.add_to_queue
import synara.synara.generated.resources.play
import synara.synara.generated.resources.songs

class AlbumScreen(private val albumId: PlatformUUID) : Screen {

    override val key: ScreenKey = "AlbumScreen_$albumId"

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<AlbumScreenModel> { parametersOf(albumId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(state.album?.name ?: "") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            state.album?.let { album ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        AlbumHeader(album, state.songs, screenModel)
                    }

                    itemsIndexed(state.songs) { index, song ->
                        val currentSong by screenModel.playerModel.currentSong.collectAsState()
                        SongItem(
                            song = song,
                            index = index,
                            isCurrent = currentSong?.id == song.id,
                            onClick = { screenModel.playAlbum(startIndex = index) },
                            onPlayNext = { screenModel.playNext(song) },
                            onMoreOptions = { /* TODO */ }
                        )
                    }
                }
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    @Composable
    private fun AlbumHeader(album: Album, songs: List<UserSong>, screenModel: AlbumScreenModel) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(MaterialTheme.shapes.medium)
            ) {
                AsyncImage(
                    model = rememberImageRequest(album.coverId, size = 200.dp),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = album.artists.joinArtists(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${songs.size} ${stringResource(Res.string.songs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { screenModel.playAlbum() },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.play))
                    }

                    OutlinedButton(
                        onClick = { screenModel.addToQueue() }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.add_to_queue))
                    }
                }
            }
        }
    }
}
