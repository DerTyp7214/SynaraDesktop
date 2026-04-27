package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.data.BaseSong
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.verticalScrollScrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSongArtistsDialog(
    isOpen: Boolean,
    song: BaseSong,
    onDismissRequest: () -> Unit,
    onSave: (List<Artist>) -> Unit,
    artistService: IArtistService = koinInject()
) {
    val scope = rememberCoroutineScope()
    var selectedArtists by remember(song) { mutableStateOf(song.artists) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<Artist>()) }
    var isSearching by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300.milliseconds)
        isSearching = true
        try {
            searchResults = artistService.rankedSearch(0, 10, searchQuery).data
                .filter { result -> selectedArtists.none { it.id == result.id } }
        } catch (_: Exception) {
        } finally {
            isSearching = false
        }
    }

    fun addTopSearchResult() {
        if (searchResults.isNotEmpty()) {
            selectedArtists = selectedArtists + searchResults.first()
            searchQuery = ""
            searchResults = emptyList()
        }
    }

    SynaraDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .padding(24.dp)
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScrollScrim(scrollState, applyScroll = true),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.edit_song_artists_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                if (selectedArtists.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedArtists.forEach { selected ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    selectedArtists = selectedArtists.filter { it.id != selected.id }
                                },
                                label = { Text(selected.name) },
                                trailingIcon = {
                                    Icon(
                                        SynaraIcons.Close.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                avatar = {
                                    SynaraImage(
                                        imageId = selected.imageId,
                                        size = 24.dp,
                                        shape = CircleShape,
                                        fallbackIcon = SynaraIcons.Artists
                                    )
                                }
                            )
                        }
                    }
                }

                InternalTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(Res.string.edit_song_artists_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (searchResults.isNotEmpty()) {
                            IconButton(onClick = { addTopSearchResult() }) {
                                Icon(SynaraIcons.Add.get(), contentDescription = null)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTopSearchResult() }),
                    singleLine = true
                )

                if (searchQuery.length >= 2 && !isSearching) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.create_artist_x, searchQuery)) },
                        leadingContent = {
                            if (isCreating) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(SynaraIcons.Add.get(), contentDescription = null)
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = !isCreating) {
                                scope.launch {
                                    isCreating = true
                                    try {
                                        val newArtist = artistService.createArtist(searchQuery)
                                        selectedArtists = selectedArtists + newArtist
                                        searchQuery = ""
                                    } catch (_: Exception) {
                                    } finally {
                                        isCreating = false
                                    }
                                }
                            }
                    )
                }

                if (searchResults.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        searchResults.forEach { result ->
                            ListItem(
                                headlineContent = { Text(result.name) },
                                leadingContent = {
                                    SynaraImage(
                                        imageId = result.imageId,
                                        size = 40.dp,
                                        shape = CircleShape,
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                        fallbackIcon = SynaraIcons.Artists
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        selectedArtists = selectedArtists + result
                                        searchQuery = ""
                                        searchResults = emptyList()
                                    }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(selectedArtists)
                            onDismissRequest()
                        },
                        enabled = selectedArtists.isNotEmpty()
                    ) {
                        Text(stringResource(Res.string.edit_song_artists_button))
                    }
                }
            }
        }
    }
}
