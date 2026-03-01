package dev.dertyp.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.dertyp.synara.Config
import dev.dertyp.synara.SynaraView
import dev.dertyp.synara.di.initializeSynara
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.WindowActions
import dev.dertyp.synara.ui.components.SynaraTray
import org.jetbrains.compose.resources.painterResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.icon
import kotlin.system.exitProcess

fun main() {
    val osName = System.getProperty("os.name").lowercase()
    val isMac = osName.contains("mac")
    val isWin = osName.contains("win")
    val isLinux = osName.contains("linux")

    val showTitleBar = System.getProperty(
        "synara.drag.enabled",
        if (isLinux) "false" else "true"
    ).toBoolean()

    if (isMac) {
        System.setProperty("apple.awt.fullWindowContent", "true")
        System.setProperty("apple.awt.transparentTitleBar", "true")
        System.setProperty("apple.awt.windowTitleVisible", "false")
    }

    initializeSynara()
    application {
        var isVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState()
        val hideOnClose by Config.hideOnClose.collectAsState()

        val windowActions = remember(windowState) {
            object : WindowActions {
                override fun toggleFullscreen() {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Fullscreen
                    }
                }

                override fun setFullscreen(enabled: Boolean) {
                    windowState.placement = if (enabled) {
                        WindowPlacement.Fullscreen
                    } else {
                        WindowPlacement.Floating
                    }
                }

                override val isFullscreen: Boolean
                    get() = windowState.placement == WindowPlacement.Fullscreen
            }
        }

        SynaraTray(
            onAction = { isVisible = !isVisible },
            onExit = {
                exitApplication()
                exitProcess(0)
            }
        )

        if (isVisible) {
            Window(
                onCloseRequest = {
                    if (hideOnClose) {
                        isVisible = false
                    } else {
                        exitApplication()
                        exitProcess(0)
                    }
                },
                state = windowState,
                title = "Synara",
                undecorated = false,
                icon = painterResource(Res.drawable.icon)
            ) {
                SideEffect {
                    if (isMac) {
                        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    } else if (isWin) {
                        window.rootPane.putClientProperty("rootPane.setupWindowDecoration", true)
                    }
                }

                val theme = SynaraAppTheme(isAppDark())
                CompositionLocalProvider(LocalWindowActions provides windowActions) {
                    MaterialTheme(
                        colorScheme = theme.colorScheme,
                        typography = theme.typography,
                        shapes = theme.shapes
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column {
                                if (showTitleBar && !windowActions.isFullscreen) {
                                    CustomSystemBar(isMac)
                                }

                                Box(modifier = Modifier.fillMaxSize()) {
                                    SynaraView()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowScope.CustomSystemBar(isMac: Boolean) {
    WindowDraggableArea {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isMac) 28.dp else 32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Synara",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
