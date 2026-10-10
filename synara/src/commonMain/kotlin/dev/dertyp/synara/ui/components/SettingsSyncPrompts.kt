package dev.dertyp.synara.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import synara.synara.generated.resources.settings_sync_conflict_keep_local
import synara.synara.generated.resources.settings_sync_conflict_message
import synara.synara.generated.resources.settings_sync_conflict_title
import synara.synara.generated.resources.settings_sync_conflict_use_remote
import synara.synara.generated.resources.settings_sync_enable_choice_message
import synara.synara.generated.resources.settings_sync_enable_choice_title
import synara.synara.generated.resources.settings_sync_enable_keep_local
import synara.synara.generated.resources.settings_sync_enable_use_remote
import synara.synara.generated.resources.settings_sync_other_device
import synara.synara.generated.resources.settings_sync_reset_accept
import synara.synara.generated.resources.settings_sync_reset_keep_local
import synara.synara.generated.resources.settings_sync_reset_message
import synara.synara.generated.resources.settings_sync_reset_title
import synara.synara.generated.resources.settings_sync_unlock_choice_message
import synara.synara.generated.resources.settings_sync_unlock_choice_title
import synara.synara.generated.resources.settings_sync_unlock_keep_local
import synara.synara.generated.resources.settings_sync_unlock_use_remote

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
    SettingsSyncEnableChoiceDialog(settingsSync)
    SettingsSyncConflictDialog(settingsSync)
    SettingsSyncResetDialog(settingsSync)

    val passphraseRequest by SettingsSyncDialogs.passphraseRequest.collectAsState()
    passphraseRequest?.let { mode ->
        SecretsPassphraseDialog(
            mode = mode,
            onDismiss = { SettingsSyncDialogs.dismissPassphrase() },
            settingsSync = settingsSync
        )
    }
}

@Composable
private fun SettingsSyncEnableChoiceDialog(settingsSync: SettingsSyncService) {
    val pendingEnableChoice by settingsSync.pendingEnableChoice.collectAsState()
    val otherDevice = stringResource(Res.string.settings_sync_other_device)
    val enableChoiceDevice = pendingEnableChoice?.deviceName?.takeIf { it.isNotBlank() } ?: otherDevice
    val keyCount = pendingEnableChoice?.keys?.size ?: 0
    val onUnlock = pendingEnableChoice?.onUnlock == true

    SynaraAlertDialog(
        isOpen = pendingEnableChoice != null,
        onDismissRequest = { settingsSync.cancelEnableChoice() },
        title = {
            Text(
                stringResource(
                    if (onUnlock) Res.string.settings_sync_unlock_choice_title else Res.string.settings_sync_enable_choice_title
                )
            )
        },
        text = {
            Text(
                if (onUnlock) {
                    stringResource(Res.string.settings_sync_unlock_choice_message)
                } else {
                    stringResource(
                        Res.string.settings_sync_enable_choice_message,
                        keyCount.toString(),
                        enableChoiceDevice
                    )
                }
            )
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { settingsSync.resolveEnableChoice(false) }) {
                    Text(
                        if (onUnlock) {
                            stringResource(Res.string.settings_sync_unlock_use_remote)
                        } else {
                            stringResource(Res.string.settings_sync_enable_use_remote, enableChoiceDevice)
                        }
                    )
                }
                TextButton(onClick = { settingsSync.resolveEnableChoice(true) }) {
                    Text(
                        stringResource(
                            if (onUnlock) Res.string.settings_sync_unlock_keep_local else Res.string.settings_sync_enable_keep_local
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { settingsSync.cancelEnableChoice() }) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
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
private fun SettingsSyncResetDialog(settingsSync: SettingsSyncService) {
    val pendingReset by settingsSync.pendingReset.collectAsState()
    val keyCount = pendingReset?.keys?.size ?: 0

    SynaraAlertDialog(
        isOpen = pendingReset != null,
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(Res.string.settings_sync_reset_title)) },
        text = { Text(stringResource(Res.string.settings_sync_reset_message, keyCount.toString())) },
        confirmButton = {
            TextButton(onClick = { settingsSync.resolveReset(true) }) {
                Text(stringResource(Res.string.settings_sync_reset_keep_local))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { settingsSync.resolveReset(false) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(Res.string.settings_sync_reset_accept))
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
                    OutlinedTextField(
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
                        OutlinedTextField(
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
