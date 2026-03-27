package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.scrobble.MusicBrainzService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.search_musicbrainz

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicBrainzSearchDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    initialQuery: String,
    onSelect: (MusicBrainzService.MbRecording) -> Unit,
    mbService: MusicBrainzService = koinInject()
) {
    val scope = rememberCoroutineScope()
    var query by remember(isOpen) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<MusicBrainzService.MbRecording>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(isOpen) {
        if (isOpen && initialQuery.isNotBlank()) {
            isSearching = true
            results = mbService.searchRecordings(initialQuery)
            isSearching = false
        }
    }

    SynaraDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxHeight(0.8f)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(Res.string.search_musicbrainz),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(12.dp))

                InternalTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.search_musicbrainz)) },
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(SynaraIcons.Close.get(), contentDescription = null)
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isSearching = true
                            results = mbService.searchRecordings(query)
                            isSearching = false
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isSearching && query.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text(stringResource(Res.string.search_musicbrainz))
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(results) { recording ->
                            val releaseId = recording.releases?.firstOrNull()?.id
                            ListItem(
                                leadingContent = {
                                    if (releaseId != null) {
                                        AsyncImage(
                                            model = "https://coverartarchive.org/release/$releaseId/front-250",
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(MaterialTheme.shapes.small),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        SynaraImage(
                                            imageId = null,
                                            size = 48.dp,
                                            shape = MaterialTheme.shapes.small,
                                            fallbackIcon = SynaraIcons.Songs
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        text = recording.title ?: "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = recording.artistCredit?.joinToString("") {
                                            (it.name ?: it.artist?.name ?: "") + (it.joinphrase ?: "")
                                        } ?: "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingContent = {
                                    recording.releases?.firstOrNull()?.title?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier.widthIn(max = 150.dp),
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    onSelect(recording)
                                    onDismissRequest()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
