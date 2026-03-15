package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.core.parseVersions
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.AlbumItem
import dev.dertyp.synara.viewmodels.ArtistAlbumsScreenModel
import org.jetbrains.compose.resources.stringResource
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.versions_count

class ArtistAlbumsScreen(private val artistId: PlatformUUID) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ArtistAlbumsScreenModel> { parametersOf(artistId) }
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
                                is ArtistAlbumsScreenModel.ArtistAlbumsState.Success -> s.artist?.name ?: ""
                                is ArtistAlbumsScreenModel.ArtistAlbumsState.Loading -> s.artist?.name ?: ""
                                else -> ""
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val currentState = state) {
                    is ArtistAlbumsScreenModel.ArtistAlbumsState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is ArtistAlbumsScreenModel.ArtistAlbumsState.Error -> {
                        Text(
                            text = currentState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                        )
                    }
                    is ArtistAlbumsScreenModel.ArtistAlbumsState.Success -> {
                        val lazyGridState = rememberLazyGridState()
                        val grouped = currentState.albums.groupBy { it.coverId }.values.toList()

                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 280.dp),
                                modifier = Modifier.fillMaxSize(),
                                state = lazyGridState,
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(grouped) { albumVersions ->
                                    val (album, versions) = albumVersions.parseVersions()
                                    AlbumItem(
                                        album = album,
                                        modifier = Modifier.fillMaxWidth(),
                                        subText = if (versions.size > 1) {
                                            stringResource(Res.string.versions_count, versions.size)
                                        } else {
                                            null
                                        },
                                        onClick = { navigator?.push(AlbumScreen(album.id)) }
                                    )
                                }
                            }

                            VerticalScrollbar(
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                adapter = rememberScrollbarAdapter(
                                    scrollState = lazyGridState
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
