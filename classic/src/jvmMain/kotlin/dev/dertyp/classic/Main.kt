package dev.dertyp.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import dev.dertyp.synara.SynaraView
import dev.dertyp.synara.di.initializeSynara
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark

fun main() {
    val osName = System.getProperty("os.name").lowercase()
    val isMac = osName.contains("mac")
    val isWin = osName.contains("win")

    if (isMac) {
        System.setProperty("apple.awt.fullWindowContent", "true")
        System.setProperty("apple.awt.transparentTitleBar", "true")
        System.setProperty("apple.awt.windowTitleVisible", "false")
    }

    initializeSynara()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Synara",
            undecorated = false
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
                        CustomSystemBar(isMac)
                        
                        Box(modifier = Modifier.fillMaxSize()) {
                            SynaraView()
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
