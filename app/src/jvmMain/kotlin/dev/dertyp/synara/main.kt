package dev.dertyp.synara

import dev.dertyp.synara.db.DatabaseNotAtBaseException
import dev.dertyp.synara.di.initializeSynara
import dev.dertyp.synara.ui.runTransparentWindow
import dev.dertyp.synara.ui.showLocalDataTooOld

fun main() {
    try {
        initializeSynara()
    } catch (refusal: DatabaseNotAtBaseException) {
        showLocalDataTooOld(refusal)
    }
    runTransparentWindow(
        width = 450,
        height = 300,
        minWidth = 450,
        minHeight = 300,
    ) {
        SynaraView()
    }
}
