package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.dertyp.data.Artist
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.SplitArtist
import dev.dertyp.services.IArtistService
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergeArtistDialog(
    isOpen: Boolean,
    artist: Artist,
    onDismissRequest: () -> Unit,
    onMerge: (MergeArtists) -> Unit,
    artistService: IArtistService = koinInject()
) {
    var name by remember(artist) { mutableStateOf(artist.name) }
    var imageId by remember(artist) { mutableStateOf(artist.imageId?.toString() ?: "") }
    var selectedArtists by remember(artist) { mutableStateOf(listOf(artist)) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<Artist>()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
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

    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.merge_artist_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InternalTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.merge_artist_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                InternalTextField(
                    value = imageId,
                    onValueChange = { imageId = it },
                    label = { Text(stringResource(Res.string.merge_artist_image_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(Res.string.show_artists),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedArtists.forEach { selected ->
                        InputChip(
                            selected = true,
                            onClick = {
                                if (selected.id != artist.id) {
                                    selectedArtists = selectedArtists.filter { it.id != selected.id }
                                }
                            },
                            label = { Text(selected.name) },
                            elevation = InputChipDefaults.inputChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                            trailingIcon = {
                                if (selected.id != artist.id) {
                                    Icon(
                                        SynaraIcons.Close.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
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

                InternalTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(Res.string.merge_artist_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (searchResults.isNotEmpty()) {
                            IconButton(onClick = { addTopSearchResult() }) {
                                Icon(SynaraIcons.Add.get(), contentDescription = null)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTopSearchResult() })
                )

                if (searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(searchResults) { result ->
                            ListItem(
                                headlineContent = { Text(result.name) },
                                leadingContent = {
                                    SynaraImage(
                                        imageId = result.imageId,
                                        size = 40.dp,
                                        shape = CircleShape,
                                        fallbackIcon = SynaraIcons.Artists
                                    )
                                },
                                modifier = Modifier.clickable {
                                    selectedArtists = selectedArtists + result
                                    searchQuery = ""
                                    searchResults = emptyList()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onMerge(
                        MergeArtists(
                            name = name,
                            image = imageId.ifBlank { null },
                            artistIds = selectedArtists.map { it.id }
                        )
                    )
                    onDismissRequest()
                },
                enabled = name.isNotBlank() && selectedArtists.isNotEmpty()
            ) {
                Text(stringResource(Res.string.merge_artist_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitArtistDialog(
    isOpen: Boolean,
    artist: Artist,
    onDismissRequest: () -> Unit,
    onSplit: (SplitArtist) -> Unit,
) {
    var currentName by remember { mutableStateOf("") }
    var newNames by remember(artist) { mutableStateOf(emptyList<String>()) }

    fun addName() {
        if (currentName.isNotBlank() && !newNames.contains(currentName.trim())) {
            newNames = newNames + currentName.trim()
            currentName = ""
        }
    }

    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = stringResource(Res.string.split_artist_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InternalTextField(
                    value = currentName,
                    onValueChange = { currentName = it },
                    label = { Text(stringResource(Res.string.split_artist_names_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(Res.string.split_artist_name_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addName() }),
                    trailingIcon = {
                        IconButton(onClick = { addName() }, enabled = currentName.isNotBlank()) {
                            Icon(SynaraIcons.Add.get(), contentDescription = null)
                        }
                    }
                )

                if (newNames.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        newNames.forEach { name ->
                            InputChip(
                                selected = true,
                                onClick = { newNames = newNames.filter { it != name } },
                                label = { Text(name) },
                                elevation = InputChipDefaults.inputChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                                trailingIcon = {
                                    Icon(
                                        SynaraIcons.Close.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newArtists = newNames.associateWith { null }

                    onSplit(
                        SplitArtist(
                            artistId = artist.id,
                            newArtists = newArtists
                        )
                    )
                    onDismissRequest()
                },
                enabled = newNames.size >= 2
            ) {
                Text(stringResource(Res.string.split_artist_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
