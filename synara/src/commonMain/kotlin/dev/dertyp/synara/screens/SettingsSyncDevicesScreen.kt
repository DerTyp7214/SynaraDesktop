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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.ClientDevice
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.sync.SettingsSyncService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.settings_sync_delete_device
import synara.synara.generated.resources.settings_sync_delete_device_message
import synara.synara.generated.resources.settings_sync_devices_empty
import synara.synara.generated.resources.settings_sync_devices_refresh
import synara.synara.generated.resources.settings_sync_devices_title
import synara.synara.generated.resources.settings_sync_last_seen
import synara.synara.generated.resources.settings_sync_this_device

class SettingsSyncDevicesScreen : Screen {

    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val settingsSync = koinInject<SettingsSyncService>()

        val devices by settingsSync.devices.collectAsState()
        val isBusy by settingsSync.isBusy.collectAsState()
        val currentDeviceId = remember(settingsSync) { settingsSync.currentDeviceId }

        LaunchedEffect(Unit) {
            settingsSync.refreshDevices()
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    title = { Text(stringResource(Res.string.settings_sync_devices_title)) },
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
                            IconButton(onClick = { settingsSync.refreshDevices() }) {
                                Icon(
                                    imageVector = SynaraIcons.Refresh.get(),
                                    contentDescription = stringResource(Res.string.settings_sync_devices_refresh)
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
                            text = stringResource(Res.string.settings_sync_devices_empty),
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
                            items(devices, key = { it.deviceId }) { device ->
                                DeviceCard(
                                    device = device,
                                    isCurrent = device.deviceId == currentDeviceId,
                                    onDelete = { settingsSync.deleteDevice(device.deviceId) },
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
        device: ClientDevice,
        isCurrent: Boolean,
        onDelete: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        var showDeleteConfirm by remember { mutableStateOf(false) }
        val name = device.name.takeIf { it.isNotBlank() } ?: device.deviceId

        SettingsCard(modifier = modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = platformIcon(device.platform).get(),
                    contentDescription = null,
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (isCurrent) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_sync_this_device),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    if (device.platform.isNotBlank()) {
                        Text(
                            text = device.platform,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = stringResource(
                            Res.string.settings_sync_last_seen,
                            device.lastSeenAt.formatDateTime()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!isCurrent) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = SynaraIcons.Delete.get(),
                            contentDescription = stringResource(Res.string.settings_sync_delete_device),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        SynaraAlertDialog(
            isOpen = showDeleteConfirm,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.settings_sync_delete_device)) },
            text = { Text(stringResource(Res.string.settings_sync_delete_device_message, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(Res.string.settings_sync_delete_device))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    private fun platformIcon(platform: String): SynaraIcons = when (platform) {
        "iPhone", "iPad", "Android" -> SynaraIcons.DeviceMobile
        "Macintosh", "Windows", "Linux", "Desktop" -> SynaraIcons.DeviceDesktop
        else -> SynaraIcons.DeviceGeneric
    }
}
