package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.MediaCollection
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.viewmodels.CollectionsScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class CollectionsScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<CollectionsScreenModel>()
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()

        var showCreateDialog by remember { mutableStateOf(false) }
        var deleteTarget by remember { mutableStateOf<MediaCollection?>(null) }

        LaunchedEffect(Unit) {
            screenModel.refresh()
        }

        val lazyListState = rememberLazyListState()

        Scaffold(containerColor = Color.Transparent) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.collections),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Button(onClick = { showCreateDialog = true }) {
                                Icon(
                                    SynaraIcons.Add.get(),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.collection_create))
                            }
                        }
                    }

                    if (state.isLoading && state.collections.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (state.collections.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.collection_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(state.collections.size, key = { state.collections[it].id.toString() }) { index ->
                            val collection = state.collections[index]
                            CollectionCard(
                                collection = collection,
                                onClick = { navigator.push(CollectionScreen(collection.id)) },
                                onDelete = { deleteTarget = collection }
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        if (showCreateDialog) {
            CollectionEditDialog(
                title = stringResource(Res.string.collection_create),
                initialName = "",
                initialDescription = "",
                onConfirm = { name, description ->
                    screenModel.createCollection(name, description)
                    showCreateDialog = false
                },
                onDismissRequest = { showCreateDialog = false }
            )
        }

        deleteTarget?.let { collection ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(collection.name) },
                text = { Text(stringResource(Res.string.collection_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.deleteCollection(collection.id)
                        deleteTarget = null
                    }) {
                        Text(
                            stringResource(Res.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun CollectionCard(
        collection: MediaCollection,
        onClick: () -> Unit,
        onDelete: () -> Unit
    ) {
        SettingsCard(onClick = onClick) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SynaraImage(
                    imageId = collection.imageId,
                    size = 56.dp,
                    shape = MaterialTheme.shapes.small,
                    fallbackIcon = SynaraIcons.Collections
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = collection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    collection.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${collection.songCount} ${stringResource(Res.string.songs)} · ${collection.totalSizeBytes.formatBytes()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        SynaraIcons.Delete.get(),
                        contentDescription = stringResource(Res.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun CollectionEditDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InternalTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.collection_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                InternalTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.collection_description)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
