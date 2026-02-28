package dev.dertyp.synara.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.window.ApplicationScope
import dev.dertyp.synara.tray.createSynaraTray
import dev.dertyp.synara.ui.models.TrayState
import org.koin.compose.koinInject

@Composable
fun ApplicationScope.SynaraTray(
    onAction: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val tray = remember { createSynaraTray() }
    val trayState = koinInject<TrayState>()
    val badgeColor by trayState.badgeColor.collectAsState()

    DisposableEffect(Unit) {
        tray.show(
            iconPath = "tray.svg", 
            tooltip = "Synara",
            onAction = onAction,
            onExit = onExit
        )

        onDispose {
            tray.hide()
        }
    }

    LaunchedEffect(badgeColor) {
        tray.setBadge(badgeColor)
    }
}
