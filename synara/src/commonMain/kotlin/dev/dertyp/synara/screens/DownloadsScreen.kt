package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SongItem
import dev.dertyp.synara.viewmodels.DownloadsScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class DownloadsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<DownloadsScreenModel>()
        val navigator = LocalNavigator.current
        
        val queue by screenModel.queue?.collectAsState(emptyList()) ?: remember { mutableStateOf(emptyList()) }
        val currentDownload by screenModel.currentDownload?.collectAsState(null) ?: remember { mutableStateOf(null) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(Res.string.downloads)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(
                                SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            if (currentDownload == null && queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(Res.string.downloads_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    currentDownload?.let { download ->
                        item {
                            Text(
                                stringResource(Res.string.currently_downloading),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            DownloadItem(download)
                            
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    if (queue.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(Res.string.download_queue),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(queue) { song ->
                            SongItem(
                                song = song,
                                showCover = true,
                                showLike = false,
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadItem(download: dev.dertyp.synara.services.DownloadProgress) {
        val progress = if (download.totalBytes > 0) {
            download.downloadedBytes.toFloat() / download.totalBytes.toFloat()
        } else 0f

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = download.song.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = download.song.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Text(
                        text = "${download.downloadedBytes.formatBytes()} / ${download.totalBytes.formatBytes()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}
