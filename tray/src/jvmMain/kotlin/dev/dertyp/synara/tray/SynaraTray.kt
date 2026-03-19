package dev.dertyp.synara.tray

import dev.dertyp.synara.utils.OSUtils

actual fun createSynaraTray(): SynaraTray {
    return when {
        OSUtils.isLinux -> LinuxSynaraTray()
        OSUtils.isMac -> MacSynaraTray()
        OSUtils.isWindows -> WindowsSynaraTray()
        else -> LinuxSynaraTray()
    }
}
