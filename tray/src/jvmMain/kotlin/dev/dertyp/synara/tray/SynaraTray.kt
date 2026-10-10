package dev.dertyp.synara.tray

import dev.dertyp.synara.utils.OSUtils

actual fun createSynaraTray(onError: (message: String, error: Throwable) -> Unit): SynaraTray {
    return when {
        OSUtils.isLinux -> LinuxSynaraTray(onError)
        OSUtils.isMac -> MacSynaraTray(onError)
        OSUtils.isWindows -> WindowsSynaraTray(onError)
        else -> LinuxSynaraTray(onError)
    }
}
