package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.MediaCollection
import dev.dertyp.services.ICollectionService
import dev.dertyp.synara.ui.models.SnackbarManager
import org.jetbrains.compose.resources.getString
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.verticalScrollScrim
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun AddToCollectionDialog(
    isOpen: Boolean,
    itemType: CollectionItemType,
    itemId: PlatformUUID,
    onDismissRequest: () -> Unit,
    collectionService: ICollectionService = koinInject(),
    snackbarManager: SnackbarManager = koinInject()
) {
    val scope = rememberCoroutineScope()
    CollectionPickerDialog(
        isOpen = isOpen,
        onCollectionSelected = { collection ->
            scope.launch {
                try {
                    collectionService.addItem(collection.id, itemType, itemId)
                    snackbarManager.showSnackbar(getString(Res.string.added_to_collection, collection.name))
                } catch (_: Exception) {
                }
            }
        },
        onDismissRequest = onDismissRequest
    )
}

@Composable
fun CollectionPickerDialog(
    isOpen: Boolean,
    onCollectionSelected: (MediaCollection) -> Unit,
    onDismissRequest: () -> Unit,
    collectionService: ICollectionService = koinInject()
) {
    if (!isOpen) return

    val scope = rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<MediaCollection>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }

    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey) {
        isLoading = true
        collections = try {
            collectionService.allCollections()
        } catch (_: Exception) {
            emptyList()
        }
        isLoading = false
    }

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(Res.string.select_collection),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val lazyListState = rememberLazyListState()
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScrollScrim(lazyListState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { showCreate = true },
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    SynaraIcons.Add.get(),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = stringResource(Res.string.new_collection),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (isLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    items(collections) { collection ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onCollectionSelected(collection)
                                    onDismissRequest()
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SynaraImage(
                                    imageId = collection.imageId,
                                    size = 48.dp,
                                    fallbackIcon = SynaraIcons.Collections
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = collection.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(Res.string.collection_create)) },
            text = {
                InternalTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.collection_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                collectionService.createCollection(InsertableCollection(name = name))
                            } catch (_: Exception) {
                            }
                            showCreate = false
                            reloadKey++
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(Res.string.collection_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}
