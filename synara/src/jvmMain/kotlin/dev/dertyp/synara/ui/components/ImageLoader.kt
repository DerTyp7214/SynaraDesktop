package dev.dertyp.synara.ui.components

import coil3.PlatformContext
import dev.dertyp.synara.services.LocalStorageService
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

actual fun getImageCacheDir(context: PlatformContext): String {
    val storageService = getKoin().get<LocalStorageService>()
    val cacheDir = File(storageService.getCacheDir(), "images")

    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    return cacheDir.absolutePath
}
