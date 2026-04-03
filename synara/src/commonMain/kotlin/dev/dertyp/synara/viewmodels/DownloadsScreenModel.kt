package dev.dertyp.synara.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import dev.dertyp.synara.services.IDownloadManager
import org.koin.core.component.KoinComponent

class DownloadsScreenModel : ScreenModel, KoinComponent {
    val downloadManager: IDownloadManager? by injectOrNull()

    val queue = downloadManager?.queue
    val currentDownload = downloadManager?.currentDownload
}

inline fun <reified T : Any> KoinComponent.injectOrNull() = lazy {
    try {
        getKoin().getOrNull<T>()
    } catch (_: Exception) {
        null
    }
}
