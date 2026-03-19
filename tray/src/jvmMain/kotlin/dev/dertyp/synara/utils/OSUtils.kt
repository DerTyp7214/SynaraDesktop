package dev.dertyp.synara.utils

actual object OSUtils {
    private val osName = System.getProperty("os.name").lowercase()

    actual val isWindows: Boolean = osName.contains("win")
    actual val isMac: Boolean = osName.contains("mac")
    actual val isLinux: Boolean = !isWindows && !isMac
}
