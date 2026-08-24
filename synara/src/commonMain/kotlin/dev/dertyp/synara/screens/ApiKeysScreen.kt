package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.ApiKeyInfo
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.ApiKeysScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class ApiKeysScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ApiKeysScreenModel>()
        val navigator = LocalNavigator.currentOrThrow
        val snackbarManager = koinInject<SnackbarManager>()
        val clipboard = LocalClipboardManager.current
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var showCreateDialog by remember { mutableStateOf(false) }
        var revokeTarget by remember { mutableStateOf<ApiKeyInfo?>(null) }
        var revealedSecret by remember { mutableStateOf<String?>(null) }

        val lazyListState = rememberLazyListState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.api_keys),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                SynaraIcons.Add.get(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.api_key_create))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.isLoading && state.keys.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (state.keys.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.api_keys_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(state.keys.size, key = { state.keys[it].id.toString() }) { index ->
                        val key = state.keys[index]
                        ApiKeyCard(
                            key = key,
                            onReveal = {
                                scope.launch {
                                    val secret = screenModel.getKeyString(key.id)
                                    if (secret != null) {
                                        revealedSecret = secret
                                    } else {
                                        snackbarManager.showSnackbar(getString(Res.string.api_key_no_secret))
                                    }
                                }
                            },
                            onRevoke = { revokeTarget = key }
                        )
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        if (showCreateDialog) {
            CreateApiKeyDialog(
                screenModel = screenModel,
                onCreated = { secret -> revealedSecret = secret },
                onDismissRequest = { showCreateDialog = false }
            )
        }

        revealedSecret?.let { secret ->
            AlertDialog(
                onDismissRequest = { revealedSecret = null },
                title = { Text(stringResource(Res.string.api_keys)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(Res.string.api_key_secret_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SelectionContainer {
                            Text(
                                text = secret,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(12.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(secret))
                        scope.launch {
                            snackbarManager.showSnackbar(getString(Res.string.copied_to_clipboard))
                        }
                    }) {
                        Text(stringResource(Res.string.copy))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { revealedSecret = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }

        revokeTarget?.let { key ->
            AlertDialog(
                onDismissRequest = { revokeTarget = null },
                title = { Text(key.label) },
                text = { Text(stringResource(Res.string.api_key_revoke_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.revokeKey(key.id)
                        revokeTarget = null
                    }) {
                        Text(
                            stringResource(Res.string.api_key_revoke),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { revokeTarget = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun ApiKeyCard(
        key: ApiKeyInfo,
        onReveal: () -> Unit,
        onRevoke: () -> Unit
    ) {
        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    SynaraIcons.Key.get(),
                    contentDescription = null,
                    tint = if (key.isRevoked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (key.isRevoked) {
                            Text(
                                text = stringResource(Res.string.api_key_revoked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (key.scopes.isNotEmpty()) {
                        Text(
                            text = key.scopes.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stringResource(Res.string.api_key_created, key.createdAt.formatDateTime()) +
                                " · " + (key.lastUsed?.let {
                            stringResource(Res.string.api_key_last_used, it.formatDateTime())
                        } ?: stringResource(Res.string.api_key_never_used)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!key.isRevoked) {
                    IconButton(onClick = onReveal) {
                        Icon(
                            SynaraIcons.Info.get(),
                            contentDescription = stringResource(Res.string.api_key_reveal)
                        )
                    }
                    IconButton(onClick = onRevoke) {
                        Icon(
                            SynaraIcons.Delete.get(),
                            contentDescription = stringResource(Res.string.api_key_revoke),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CreateApiKeyDialog(
        screenModel: ApiKeysScreenModel,
        onCreated: (String) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var label by remember { mutableStateOf("") }
        val selectedScopes = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(Res.string.api_key_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InternalTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(Res.string.api_key_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(Res.string.api_key_scopes),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.availableScopes.forEach { scopeInfo ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = scopeInfo.id in selectedScopes,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedScopes.add(scopeInfo.id)
                                        else selectedScopes.remove(scopeInfo.id)
                                    }
                                )
                                Column {
                                    Text(scopeInfo.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = scopeInfo.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val secret = screenModel.createKey(label, selectedScopes.toList())
                            onDismissRequest()
                            if (secret != null) onCreated(secret)
                        }
                    },
                    enabled = label.isNotBlank()
                ) {
                    Text(stringResource(Res.string.api_key_create))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}
