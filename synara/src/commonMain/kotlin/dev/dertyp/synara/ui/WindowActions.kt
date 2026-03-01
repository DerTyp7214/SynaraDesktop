package dev.dertyp.synara.ui

import androidx.compose.runtime.compositionLocalOf

interface WindowActions {
    fun toggleFullscreen()
    fun setFullscreen(enabled: Boolean)
    val isFullscreen: Boolean
    fun setCursorVisible(enabled: Boolean)
}

internal class NoOpWindowActions : WindowActions {
    override fun toggleFullscreen() {}
    override fun setFullscreen(enabled: Boolean) {}
    override val isFullscreen: Boolean = false
    override fun setCursorVisible(enabled: Boolean) {}
}

val LocalWindowActions = compositionLocalOf<WindowActions> { NoOpWindowActions() }
