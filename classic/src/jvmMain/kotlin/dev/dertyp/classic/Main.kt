package dev.dertyp.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dertyp.synara.Config
import dev.dertyp.synara.SynaraView
import dev.dertyp.synara.di.initializeSynara
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.LocalIconFilled
import dev.dertyp.synara.ui.LocalIconPack
import dev.dertyp.synara.ui.LocalIconStyle
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.WindowActions
import dev.dertyp.synara.ui.components.SynaraTray
import dev.dertyp.synara.ui.models.PerformanceMonitor
import org.jetbrains.compose.resources.painterResource
import org.koin.core.context.GlobalContext
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.icon
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
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

        val transparentCursor = remember {
            val cursorImg = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(cursorImg, Point(0, 0), "blank cursor")
        }

        SynaraTray(
            onAction = { isVisible = !isVisible },
            onExit = {
                exitApplication()
                exitProcess(0)
            }
        )

        if (isVisible) {
            val performanceMonitor = remember {
                try {
                    GlobalContext.get().get<PerformanceMonitor>()
                } catch (_: Exception) {
                    null
                }
            }

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
                LaunchedEffect(window.graphicsConfiguration) {
                    snapshotFlow { window.graphicsConfiguration.device.displayMode.refreshRate }
                        .collect { rate ->
                            if (rate > 0) performanceMonitor?.updateMaxFps(rate)
                        }
                }

                SideEffect {
                    if (isMac) {
                        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    } else if (isWin) {
                        window.rootPane.putClientProperty("rootPane.setupWindowDecoration", true)
                    }
                }

                val windowActions = remember(windowState, window) {
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

                        override fun setCursorVisible(enabled: Boolean) {
                            window.cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR) else transparentCursor
                        }
                    }
                }

                val theme = SynaraAppTheme(isAppDark())
                val iconPackType by Config.iconPack.collectAsState()
                val iconStyleId by Config.iconStyle.collectAsState()
                val iconFilled by Config.iconFilled.collectAsState()

                val iconPack = iconPackType.getPack()
                val iconStyle = remember(iconPack, iconStyleId) {
                    iconPack.getStyle(iconStyleId)
                }

                CompositionLocalProvider(
                    LocalWindowActions provides windowActions,
                    LocalIconPack provides iconPack,
                    LocalIconStyle provides iconStyle,
                    LocalIconFilled provides iconFilled
                ) {
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
