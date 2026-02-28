package dev.dertyp.synara.tray

import androidx.compose.ui.graphics.Color

interface SynaraTray {
    fun show(
        iconPath: String,
        tooltip: String = "Synara",
        onAction: () -> Unit = {},
        onExit: () -> Unit = {}
    )
    fun setBadge(color: Color?)
    fun hide()
}

expect fun createSynaraTray(): SynaraTray
