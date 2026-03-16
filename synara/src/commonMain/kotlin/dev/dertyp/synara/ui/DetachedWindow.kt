@file:JvmName("DetachedWindowCommon")
package dev.dertyp.synara.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

interface DetachedWindowActions {
    fun close()
}

val LocalDetachedWindowActions = staticCompositionLocalOf<DetachedWindowActions?> { null }

@Composable
expect fun DetachedWindow(
    isOpen: Boolean,
    onCloseRequest: () -> Unit,
    title: String,
    content: @Composable () -> Unit
)
