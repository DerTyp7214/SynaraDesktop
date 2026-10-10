package dev.dertyp.synara.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.tray.createSynaraTray
import dev.dertyp.synara.ui.models.TrayState
import org.koin.compose.koinInject

private val TRAY_TAG = LogTag("tray")

@Composable
fun SynaraTray(
    onAction: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val logger = koinInject<Logger>()
    val tray = remember { createSynaraTray { message, error -> logger.error(TRAY_TAG, message, error) } }
    val trayState = koinInject<TrayState>()
    val badgeColor by trayState.badgeColor.collectAsState()

    DisposableEffect(Unit) {
        tray.show(
            iconPath = "tray.png",
            tooltip = "Synara",
            onAction = {
                Snapshot.withMutableSnapshot {
                    onAction()
                }
            },
            onExit = {
                Snapshot.withMutableSnapshot {
                    onExit()
                }
            }
        )

        onDispose {
            tray.hide()
        }
    }

    LaunchedEffect(badgeColor) {
        tray.setBadge(badgeColor)
    }
}
