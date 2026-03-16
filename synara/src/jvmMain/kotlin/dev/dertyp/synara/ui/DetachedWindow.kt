@file:JvmName("DetachedWindowJvm")
package dev.dertyp.synara.ui

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposePanel
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

@Composable
actual fun DetachedWindow(
    isOpen: Boolean,
    onCloseRequest: () -> Unit,
    title: String,
    content: @Composable () -> Unit
) {
    val currentContent by rememberUpdatedState(content)
    val currentOnClose by rememberUpdatedState(onCloseRequest)
    val isOpenState = remember { mutableStateOf(isOpen) }

    SideEffect {
        isOpenState.value = isOpen
    }

    val frameState = remember {
        object {
            var frame: JFrame? = null
        }
    }

    LaunchedEffect(Unit) {
        SwingUtilities.invokeLater {
            val f = JFrame(title)
            frameState.frame = f
            val cp = ComposePanel()

            f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            f.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    currentOnClose()
                }
            })

            f.contentPane.add(cp)
            f.setSize(800, 600)
            f.setLocationRelativeTo(null)

            cp.setContent {
                if (isOpenState.value) {
                    val actions = remember {
                        object : DetachedWindowActions {
                            override fun close() {
                                currentOnClose()
                            }
                        }
                    }
                    CompositionLocalProvider(LocalDetachedWindowActions provides actions) {
                        currentContent()
                    }
                }
            }

            f.isVisible = isOpenState.value
        }
    }

    LaunchedEffect(isOpen) {
        SwingUtilities.invokeLater {
            frameState.frame?.isVisible = isOpen
            if (isOpen) {
                frameState.frame?.toFront()
            }
        }
    }

    LaunchedEffect(title) {
        SwingUtilities.invokeLater {
            frameState.frame?.title = title
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            SwingUtilities.invokeLater {
                frameState.frame?.dispose()
                frameState.frame = null
            }
        }
    }
}
