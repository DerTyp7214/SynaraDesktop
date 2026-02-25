package dev.dertyp.synara

import dev.dertyp.synara.ui.runTransparentWindow

fun main() = runTransparentWindow(
    width = 450,
    height = 300,
    minWidth = 450,
    minHeight = 300,
) {
    SynaraView()
}
