package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.data.Artist
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.SplitArtist
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
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScrollScrim(scrollState, applyScroll = true),
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
    artistService: IArtistService = koinInject()
) {
    var currentName by remember { mutableStateOf("") }
    var selectedArtists by remember(artist) { mutableStateOf(emptyList<Triple<String, PlatformUUID?, PlatformUUID?>>()) }
    var searchResults by remember { mutableStateOf(emptyList<Artist>()) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(currentName) {
        if (currentName.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300.milliseconds)
        isSearching = true
        try {
            searchResults = artistService.rankedSearch(0, 10, currentName).data
                .filter { result -> result.id != artist.id && selectedArtists.none { it.second == result.id } }
        } catch (_: Exception) {
        } finally {
            isSearching = false
        }
    }

    fun addName() {
        if (currentName.isNotBlank() && selectedArtists.none { it.first.equals(currentName.trim(), ignoreCase = true) }) {
            selectedArtists = selectedArtists + Triple(currentName.trim(), null, null)
            currentName = ""
            searchResults = emptyList()
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
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScrollScrim(scrollState, applyScroll = true),
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
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { addName() }, enabled = currentName.isNotBlank()) {
                                Icon(SynaraIcons.Add.get(), contentDescription = null)
                            }
                        }
                    }
                )

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
                                        fallbackIcon = SynaraIcons.Artists
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        if (selectedArtists.none { it.second == result.id }) {
                                            selectedArtists = selectedArtists + Triple(result.name, result.id, result.imageId)
                                            currentName = ""
                                            searchResults = emptyList()
                                        }
                                    }
                            )
                        }
                    }
                }

                if (selectedArtists.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedArtists.forEach { (name, id, imageId) ->
                            InputChip(
                                selected = true,
                                onClick = { selectedArtists = selectedArtists.filter { it.first != name || it.second != id } },
                                label = { Text(name) },
                                elevation = InputChipDefaults.inputChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
                                trailingIcon = {
                                    Icon(
                                        SynaraIcons.Close.get(),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                avatar = if (id != null) {
                                    {
                                        SynaraImage(
                                            imageId = imageId,
                                            size = 24.dp,
                                            shape = CircleShape,
                                            fallbackIcon = SynaraIcons.Artists
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newArtists = selectedArtists.associate { it.first to it.second }

                    onSplit(
                        SplitArtist(
                            artistId = artist.id,
                            newArtists = newArtists
                        )
                    )
                    onDismissRequest()
                },
                enabled = selectedArtists.size >= 2
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetArtistGroupDialog(
    isOpen: Boolean,
    artist: Artist,
    onDismissRequest: () -> Unit,
    onSave: (List<Artist>?) -> Unit,
    artistService: IArtistService = koinInject()
) {
    val scope = rememberCoroutineScope()
    var selectedArtists by remember(artist) { mutableStateOf(artist.artists) }
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
                .filter { result -> result.id != artist.id && selectedArtists.none { it.id == result.id } }
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
                text = stringResource(Res.string.set_artist_group_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScrollScrim(scrollState, applyScroll = true),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.set_artist_group_description),
                    style = MaterialTheme.typography.bodyMedium
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
                                elevation = InputChipDefaults.inputChipElevation(elevation = 0.dp, hoveredElevation = 0.dp, pressedElevation = 0.dp),
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
                    placeholder = { Text(stringResource(Res.string.set_artist_group_search_placeholder)) },
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
                    keyboardActions = KeyboardActions(onDone = { addTopSearchResult() })
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(selectedArtists.ifEmpty { null })
                    onDismissRequest()
                }
            ) {
                Text(stringResource(Res.string.set_artist_group_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
