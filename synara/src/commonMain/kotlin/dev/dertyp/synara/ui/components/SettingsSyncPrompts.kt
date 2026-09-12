package dev.dertyp.synara.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.dertyp.synara.Config
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.sync.SecretsLockState
import dev.dertyp.synara.sync.SettingsSyncService
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.secrets_sync_enter_passphrase
import synara.synara.generated.resources.secrets_sync_passphrase_confirm_placeholder
import synara.synara.generated.resources.secrets_sync_passphrase_mismatch
import synara.synara.generated.resources.secrets_sync_passphrase_placeholder
import synara.synara.generated.resources.secrets_sync_passphrase_setup_message
import synara.synara.generated.resources.secrets_sync_passphrase_wrong
import synara.synara.generated.resources.secrets_sync_set_passphrase
import synara.synara.generated.resources.secrets_sync_state_connecting
import synara.synara.generated.resources.secrets_sync_summary
import synara.synara.generated.resources.secrets_sync_title
import synara.synara.generated.resources.settings_queue_sync_summary
import synara.synara.generated.resources.settings_queue_sync_title
import synara.synara.generated.resources.settings_settings_sync_summary
import synara.synara.generated.resources.settings_settings_sync_title
import synara.synara.generated.resources.settings_sync_conflict_keep_local
import synara.synara.generated.resources.settings_sync_conflict_message
import synara.synara.generated.resources.settings_sync_conflict_title
import synara.synara.generated.resources.settings_sync_conflict_use_remote
import synara.synara.generated.resources.settings_sync_device_name_title
import synara.synara.generated.resources.settings_sync_other_device
import synara.synara.generated.resources.sync_setup_description
import synara.synara.generated.resources.sync_setup_enable
import synara.synara.generated.resources.sync_setup_not_now
import synara.synara.generated.resources.sync_setup_title

enum class PassphraseMode { Setup, Enter }

object SettingsSyncDialogs {
    private val _passphraseRequest = MutableStateFlow<PassphraseMode?>(null)
    val passphraseRequest: StateFlow<PassphraseMode?> = _passphraseRequest.asStateFlow()

    fun requestPassphrase(mode: PassphraseMode) {
        _passphraseRequest.value = mode
    }

    fun dismissPassphrase() {
        _passphraseRequest.value = null
    }
}

@Composable
fun SettingsSyncPrompts(settingsSync: SettingsSyncService = koinInject()) {
    SettingsSyncConflictDialog(settingsSync)

    val passphraseRequest by SettingsSyncDialogs.passphraseRequest.collectAsState()
    passphraseRequest?.let { mode ->
        SecretsPassphraseDialog(
            mode = mode,
            onDismiss = { SettingsSyncDialogs.dismissPassphrase() },
            settingsSync = settingsSync
        )
    }

    SyncSetupDialog(settingsSync)
}

@Composable
private fun SettingsSyncConflictDialog(settingsSync: SettingsSyncService) {
    val pendingConflict by settingsSync.pendingConflict.collectAsState()
    val otherDevice = stringResource(Res.string.settings_sync_other_device)
    val conflictDevice = pendingConflict?.deviceName?.takeIf { it.isNotBlank() } ?: otherDevice
    val keyCount = pendingConflict?.keys?.size ?: 0

    SynaraAlertDialog(
        isOpen = pendingConflict != null,
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(Res.string.settings_sync_conflict_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.settings_sync_conflict_message,
                    keyCount.toString(),
                    conflictDevice
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { settingsSync.resolveConflict(true) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(Res.string.settings_sync_conflict_keep_local))
            }
        },
        dismissButton = {
            TextButton(onClick = { settingsSync.resolveConflict(false) }) {
                Text(stringResource(Res.string.settings_sync_conflict_use_remote, conflictDevice))
            }
        }
    )
}

@Composable
fun SecretsPassphraseDialog(
    mode: PassphraseMode,
    onDismiss: () -> Unit,
    settingsSync: SettingsSyncService = koinInject()
) {
    val lockState by settingsSync.secretsLockState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var wrongPassphrase by remember { mutableStateOf(false) }

    val isSetup = when (lockState) {
        SecretsLockState.NEEDS_SETUP -> true
        SecretsLockState.NEEDS_PASSPHRASE -> false
        else -> mode == PassphraseMode.Setup
    }
    val isWaiting = lockState == SecretsLockState.DISABLED || lockState == SecretsLockState.UNLOCKED
    val mismatch = isSetup && confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase
    val canSubmit = !isWaiting && !isSubmitting && passphrase.isNotEmpty() &&
        (!isSetup || (confirmPassphrase.isNotEmpty() && passphrase == confirmPassphrase))

    LaunchedEffect(lockState) {
        if (lockState == SecretsLockState.UNLOCKED) onDismiss()
    }

    val title = if (isSetup) {
        stringResource(Res.string.secrets_sync_set_passphrase)
    } else {
        stringResource(Res.string.secrets_sync_enter_passphrase)
    }

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isWaiting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(Res.string.secrets_sync_state_connecting),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    InternalTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            wrongPassphrase = false
                        },
                        label = { Text(stringResource(Res.string.secrets_sync_passphrase_placeholder)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isSetup) {
                        InternalTextField(
                            value = confirmPassphrase,
                            onValueChange = { confirmPassphrase = it },
                            label = {
                                Text(stringResource(Res.string.secrets_sync_passphrase_confirm_placeholder))
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !isSubmitting,
                            isError = mismatch,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (mismatch) {
                            Text(
                                text = stringResource(Res.string.secrets_sync_passphrase_mismatch),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Text(
                            text = stringResource(Res.string.secrets_sync_passphrase_setup_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (wrongPassphrase) {
                        Text(
                            text = stringResource(Res.string.secrets_sync_passphrase_wrong),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        isSubmitting = true
                        val accepted = settingsSync.setPassphrase(passphrase)
                        isSubmitting = false
                        if (accepted) onDismiss() else wrongPassphrase = true
                    }
                },
                enabled = canSubmit
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                } else {
                    Text(title)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
private fun SyncSetupDialog(settingsSync: SettingsSyncService) {
    val setupShown by Config.syncSetupShown.collectAsState()
    val isQueueSyncEnabled by Config.isQueueSyncEnabled.collectAsState()
    val isSettingsSyncEnabled by Config.isSettingsSyncEnabled.collectAsState()
    val lockState by settingsSync.secretsLockState.collectAsState()

    var isOpen by remember { mutableStateOf(false) }
    var awaitingSecrets by remember { mutableStateOf(false) }

    LaunchedEffect(setupShown, isQueueSyncEnabled, isSettingsSyncEnabled) {
        if (setupShown) return@LaunchedEffect
        if (isQueueSyncEnabled || isSettingsSyncEnabled) {
            Config.setSyncSetupShown(true)
            return@LaunchedEffect
        }
        isOpen = true
    }

    LaunchedEffect(awaitingSecrets, lockState) {
        if (!awaitingSecrets) return@LaunchedEffect
        when (lockState) {
            SecretsLockState.NEEDS_SETUP -> {
                awaitingSecrets = false
                SettingsSyncDialogs.requestPassphrase(PassphraseMode.Setup)
            }

            SecretsLockState.NEEDS_PASSPHRASE -> {
                awaitingSecrets = false
                SettingsSyncDialogs.requestPassphrase(PassphraseMode.Enter)
            }

            else -> Unit
        }
    }

    if (!isOpen) return

    var deviceName by remember { mutableStateOf(Config.queueSyncDeviceName.value) }
    var enableQueue by remember { mutableStateOf(true) }
    var enableSettings by remember { mutableStateOf(true) }
    var enableSecrets by remember { mutableStateOf(false) }

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = {
            isOpen = false
            Config.setSyncSetupShown(true)
        },
        title = { Text(stringResource(Res.string.sync_setup_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.sync_setup_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                InternalTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text(stringResource(Res.string.settings_sync_device_name_title)) },
                    placeholder = { Text(settingsSync.platformDeviceName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SyncSetupSwitch(
                    title = stringResource(Res.string.settings_queue_sync_title),
                    summary = stringResource(Res.string.settings_queue_sync_summary),
                    checked = enableQueue,
                    onCheckedChange = { enableQueue = it }
                )
                SyncSetupSwitch(
                    title = stringResource(Res.string.settings_settings_sync_title),
                    summary = stringResource(Res.string.settings_settings_sync_summary),
                    checked = enableSettings,
                    onCheckedChange = {
                        enableSettings = it
                        if (!it) enableSecrets = false
                    }
                )
                SyncSetupSwitch(
                    title = stringResource(Res.string.secrets_sync_title),
                    summary = stringResource(Res.string.secrets_sync_summary),
                    checked = enableSecrets,
                    enabled = enableSettings,
                    onCheckedChange = { enableSecrets = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val secrets = enableSettings && enableSecrets
                    Config.setQueueSyncDeviceName(deviceName.trim())
                    Config.setIsQueueSyncEnabled(enableQueue)
                    Config.setIsSettingsSyncEnabled(enableSettings)
                    Config.setIsSecretsSyncEnabled(secrets)
                    Config.setSyncSetupShown(true)
                    isOpen = false
                    if (secrets) awaitingSecrets = true
                },
                enabled = enableQueue || enableSettings
            ) {
                Text(stringResource(Res.string.sync_setup_enable))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    isOpen = false
                    Config.setSyncSetupShown(true)
                }
            ) {
                Text(stringResource(Res.string.sync_setup_not_now))
            }
        }
    )
}

@Composable
private fun SyncSetupSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
