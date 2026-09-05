package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.ListenBackupScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class ListenBackupScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<ListenBackupScreenModel>()
        val navigator = LocalNavigator.currentOrThrow
        val snackbarManager = koinInject<SnackbarManager>()
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var showResetConfirm by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.listen_backup_title),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
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
                if (state.isLoading && state.backupState == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SettingsCard {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(Res.string.listen_backup_enabled),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Switch(
                                        checked = state.enabled,
                                        onCheckedChange = screenModel::setEnabled,
                                        enabled = !state.isBusy
                                    )
                                }
                                InternalTextField(
                                    value = state.url,
                                    onValueChange = screenModel::setUrl,
                                    label = { Text(stringResource(Res.string.listen_backup_url)) },
                                    singleLine = true,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                InternalTextField(
                                    value = state.key,
                                    onValueChange = screenModel::setKey,
                                    label = { Text(stringResource(Res.string.listen_backup_key)) },
                                    singleLine = true,
                                    enabled = !state.isBusy,
                                    visualTransformation = PasswordVisualTransformation(),
                                    supportingText = if (state.backupState?.hasKey == true && state.key.isEmpty()) {
                                        { Text(stringResource(Res.string.listen_backup_key_hint)) }
                                    } else null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                InternalTextField(
                                    value = state.batchSize,
                                    onValueChange = screenModel::setBatchSize,
                                    label = { Text(stringResource(Res.string.listen_backup_batch_size)) },
                                    singleLine = true,
                                    enabled = !state.isBusy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                state.error?.let { error ->
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                state.testResult?.let { result ->
                                    val ok = result.ok
                                    val remoteCount = result.remoteListenCount
                                    val errorMessage = result.message
                                    val message = when {
                                        ok && remoteCount != null ->
                                            stringResource(Res.string.listen_backup_test_ok_with_count, remoteCount)

                                        ok -> stringResource(Res.string.listen_backup_test_ok)
                                        errorMessage != null ->
                                            stringResource(Res.string.listen_backup_test_failed_with_message, errorMessage)

                                        else -> stringResource(Res.string.listen_backup_test_failed)
                                    }
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { scope.launch { screenModel.testConnection() } },
                                        enabled = !state.isBusy
                                    ) {
                                        Text(stringResource(Res.string.listen_backup_test_connection))
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                if (screenModel.save()) {
                                                    snackbarManager.showSnackbar(getString(Res.string.listen_backup_save_success))
                                                }
                                            }
                                        },
                                        enabled = !state.isBusy
                                    ) {
                                        Text(stringResource(Res.string.save))
                                    }
                                }
                            }
                        }

                        SettingsCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(Res.string.listen_backup_status_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                val backupState = state.backupState
                                Text(
                                    text = stringResource(
                                        Res.string.listen_backup_last_sync,
                                        backupState?.lastSyncAt?.formatDateTime() ?: stringResource(Res.string.listen_backup_never_synced)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(Res.string.listen_backup_synced_count, backupState?.lastSyncedCount ?: 0),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(Res.string.listen_backup_pending_count, backupState?.pendingCount ?: 0L),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                backupState?.lastError?.let { lastError ->
                                    Text(
                                        text = stringResource(Res.string.listen_backup_last_error, lastError),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                backupState?.serverId?.let { serverId ->
                                    Text(
                                        text = stringResource(Res.string.listen_backup_server_id, serverId.toString()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                if (screenModel.syncNow()) {
                                                    snackbarManager.showSnackbar(getString(Res.string.listen_backup_sync_success))
                                                }
                                            }
                                        },
                                        enabled = !state.isBusy
                                    ) {
                                        Text(stringResource(Res.string.listen_backup_sync_now))
                                    }
                                    OutlinedButton(
                                        onClick = { showResetConfirm = true },
                                        enabled = !state.isBusy
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.listen_backup_reset_cursor),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text(stringResource(Res.string.listen_backup_reset_cursor)) },
                text = { Text(stringResource(Res.string.listen_backup_reset_cursor_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        scope.launch {
                            if (screenModel.resetCursor()) {
                                snackbarManager.showSnackbar(getString(Res.string.listen_backup_reset_success))
                            }
                        }
                    }) {
                        Text(
                            stringResource(Res.string.listen_backup_reset_cursor),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }
}
