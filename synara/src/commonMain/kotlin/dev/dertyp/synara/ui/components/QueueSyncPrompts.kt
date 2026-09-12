package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.dertyp.synara.player.QueueSyncEvent
import dev.dertyp.synara.player.QueueSyncService
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.ui.models.SnackbarManager
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.queue_sync_conflict_keep_local
import synara.synara.generated.resources.queue_sync_conflict_message
import synara.synara.generated.resources.queue_sync_conflict_title
import synara.synara.generated.resources.queue_sync_conflict_use_remote
import synara.synara.generated.resources.queue_sync_enable_choice_message
import synara.synara.generated.resources.queue_sync_enable_choice_title
import synara.synara.generated.resources.queue_sync_enable_load_remote
import synara.synara.generated.resources.queue_sync_enable_upload_local
import synara.synara.generated.resources.queue_sync_merged_toast
import synara.synara.generated.resources.queue_sync_other_device

@Composable
fun QueueSyncPrompts(
    queueSync: QueueSyncService = koinInject(),
    snackbarManager: SnackbarManager = koinInject()
) {
    val pendingEnableChoice by queueSync.pendingEnableChoice.collectAsState()
    val pendingConflict by queueSync.pendingConflict.collectAsState()

    val otherDevice = stringResource(Res.string.queue_sync_other_device)

    LaunchedEffect(queueSync) {
        queueSync.events.collect { event ->
            when (event) {
                is QueueSyncEvent.Merged -> snackbarManager.showSnackbar(
                    getString(Res.string.queue_sync_merged_toast, event.deviceName ?: otherDevice)
                )
            }
        }
    }

    val enableChoiceDevice = pendingEnableChoice?.modifiedByDeviceName ?: otherDevice

    SynaraAlertDialog(
        isOpen = pendingEnableChoice != null,
        onDismissRequest = { queueSync.cancelEnableChoice() },
        title = { Text(stringResource(Res.string.queue_sync_enable_choice_title)) },
        text = { Text(stringResource(Res.string.queue_sync_enable_choice_message, enableChoiceDevice)) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { queueSync.resolveEnableChoice(true) }) {
                    Text(stringResource(Res.string.queue_sync_enable_load_remote, enableChoiceDevice))
                }
                TextButton(onClick = { queueSync.resolveEnableChoice(false) }) {
                    Text(stringResource(Res.string.queue_sync_enable_upload_local))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { queueSync.cancelEnableChoice() }) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )

    val conflictDevice = pendingConflict?.modifiedByDeviceName ?: otherDevice

    SynaraAlertDialog(
        isOpen = pendingConflict != null,
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(Res.string.queue_sync_conflict_title)) },
        text = { Text(stringResource(Res.string.queue_sync_conflict_message, conflictDevice)) },
        confirmButton = {
            TextButton(
                onClick = { queueSync.resolveConflict(true) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(Res.string.queue_sync_conflict_keep_local))
            }
        },
        dismissButton = {
            TextButton(onClick = { queueSync.resolveConflict(false) }) {
                Text(stringResource(Res.string.queue_sync_conflict_use_remote, conflictDevice))
            }
        }
    )
}
