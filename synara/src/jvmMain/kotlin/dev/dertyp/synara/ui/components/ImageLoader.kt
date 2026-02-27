package dev.dertyp.synara.ui.components

import coil3.PlatformContext
import java.io.File

actual fun getImageCacheDir(context: PlatformContext): String {
    val os = System.getProperty("os.name").lowercase()
    val cacheDir = when {
        os.contains("win") -> File(System.getenv("LocalAppData"), "synara/cache")
        os.contains("mac") -> File(System.getProperty("user.home"), "Library/Caches/synara")
        else -> File(System.getProperty("user.home"), ".cache/synara")
    }

    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    return cacheDir.absolutePath
}
