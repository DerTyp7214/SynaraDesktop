@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.player.QueueSyncService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.queue_sync_devices_empty
import synara.synara.generated.resources.queue_sync_devices_refresh
import synara.synara.generated.resources.queue_sync_get_queue
import synara.synara.generated.resources.queue_sync_last_sync
import synara.synara.generated.resources.queue_sync_never_synced
import synara.synara.generated.resources.queue_sync_status_completed
import synara.synara.generated.resources.queue_sync_status_rejected
import synara.synara.generated.resources.queue_sync_status_timed_out
import synara.synara.generated.resources.queue_sync_status_unreachable
import synara.synara.generated.resources.queue_sync_this_device
import synara.synara.generated.resources.settings_queue_sync_devices_title

class QueueSyncDevicesScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val queueSync = koinInject<QueueSyncService>()

        val devices by queueSync.devices.collectAsState()
        val isBusy by queueSync.isBusy.collectAsState()
        val busySessionId by queueSync.busySessionId.collectAsState()
        val requestStatuses by queueSync.requestStatuses.collectAsState()

        LaunchedEffect(Unit) {
            queueSync.refreshDevices()
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    title = { Text(stringResource(Res.string.settings_queue_sync_devices_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(
                                imageVector = SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    actions = {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 16.dp).size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { queueSync.refreshDevices() }) {
                                Icon(
                                    imageVector = SynaraIcons.Refresh.get(),
                                    contentDescription = stringResource(Res.string.queue_sync_devices_refresh)
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    devices.isEmpty() && isBusy -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    devices.isEmpty() -> {
                        Text(
                            text = stringResource(Res.string.queue_sync_devices_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp)
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(devices, key = { it.sessionId.toString() }) { device ->
                                DeviceCard(
                                    device = device,
                                    status = requestStatuses[device.sessionId],
                                    isRequesting = busySessionId == device.sessionId,
                                    canRequest = busySessionId == null,
                                    onRequest = { queueSync.requestQueueFrom(device.sessionId) },
                                    modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceCard(
        device: QueueSyncDevice,
        status: ClientRequestStatus?,
        isRequesting: Boolean,
        canRequest: Boolean,
        onRequest: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        SettingsCard(modifier = modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (device.isCurrent) SynaraIcons.DeviceDesktop.get() else SynaraIcons.DeviceGeneric.get(),
                    contentDescription = null,
                    tint = if (device.isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (device.isCurrent) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(Res.string.queue_sync_this_device),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = if (device.lastSyncAt > 0) {
                            stringResource(Res.string.queue_sync_last_sync, device.lastSyncAt.formatDateTime())
                        } else {
                            stringResource(Res.string.queue_sync_never_synced)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (status != null) {
                        Text(
                            text = stringResource(
                                when (status) {
                                    ClientRequestStatus.COMPLETED -> Res.string.queue_sync_status_completed
                                    ClientRequestStatus.REJECTED -> Res.string.queue_sync_status_rejected
                                    ClientRequestStatus.TIMED_OUT -> Res.string.queue_sync_status_timed_out
                                    ClientRequestStatus.UNREACHABLE -> Res.string.queue_sync_status_unreachable
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!device.isCurrent) {
                    if (isRequesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = onRequest, enabled = canRequest) {
                            Text(stringResource(Res.string.queue_sync_get_queue))
                        }
                    }
                }
            }
        }
    }
}
