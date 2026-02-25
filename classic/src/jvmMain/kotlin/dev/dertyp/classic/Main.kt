package dev.dertyp.classic

import androidx.compose.material.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.dertyp.synara.SynaraView
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Classic Synara") {
        val theme = SynaraAppTheme(isAppDark())
        MaterialTheme(
            colorScheme = theme.colorScheme,
            typography = theme.typography,
            shapes = theme.shapes
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background
            ) {
                SynaraView()
            }
        }
    }
}
