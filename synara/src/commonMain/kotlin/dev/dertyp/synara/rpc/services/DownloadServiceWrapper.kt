package dev.dertyp.synara.rpc.services

import dev.dertyp.services.download.DownloadBackend
import dev.dertyp.services.download.DownloadQueueEntry
import dev.dertyp.services.download.DownloadSong
import dev.dertyp.services.download.FinishedDownloadQueueEntry
import dev.dertyp.services.download.IDownloadService
import dev.dertyp.services.download.LogLine
import dev.dertyp.services.download.Type
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class DownloadServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IDownloadService {
    override fun logs(): Flow<LogLine> {
        return manager.getService<IDownloadService>().logs()
    }

    override suspend fun currentDownload(): DownloadQueueEntry? {
        return manager.getService<IDownloadService>().currentDownload()
    }

    override suspend fun downloadQueue(): List<DownloadQueueEntry> {
        return manager.getService<IDownloadService>().downloadQueue()
    }

    override suspend fun finishedDownloads(): List<FinishedDownloadQueueEntry> {
        return manager.getService<IDownloadService>().finishedDownloads()
    }

    override suspend fun syncFavouritesAvailable(): Boolean {
        return manager.getService<IDownloadService>().syncFavouritesAvailable()
    }

    override suspend fun syncFavourites() {
        manager.getService<IDownloadService>().syncFavourites()
    }

    override suspend fun downloadIds(ids: List<String>, type: Type, downloader: DownloadBackend?) {
        manager.getService<IDownloadService>().downloadIds(ids, type, downloader)
    }

    override suspend fun downloadUrls(urls: List<String>) {
        manager.getService<IDownloadService>().downloadUrls(urls)
    }

    override suspend fun getDownloaderForUrl(url: String): DownloadBackend? {
        return manager.getService<IDownloadService>().getDownloaderForUrl(url)
    }

    override suspend fun existsByOriginalId(id: String, type: Type): Boolean {
        return manager.getService<IDownloadService>().existsByOriginalId(id, type)
    }

    override suspend fun setDownloadService(service: DownloadBackend) {
        manager.getService<IDownloadService>().setDownloadService(service)
    }

    override suspend fun getDownloadService(): DownloadBackend {
        return manager.getService<IDownloadService>().getDownloadService()
    }

    override suspend fun getAllDownloadServices(): List<DownloadBackend> {
        return manager.getService<IDownloadService>().getAllDownloadServices()
    }

    override suspend fun downloadAuthorized(): Boolean {
        return manager.getService<IDownloadService>().downloadAuthorized()
    }

    override fun downloadLogin(): Flow<String> {
        return manager.getService<IDownloadService>().downloadLogin()
    }

    override suspend fun tidalSyncAuthorized(): Boolean {
        return manager.getService<IDownloadService>().tidalSyncAuthorized()
    }

    override suspend fun getAuthUrl(): String {
        return manager.getService<IDownloadService>().getAuthUrl()
    }

    override suspend fun killAllChildProcesses() {
        manager.getService<IDownloadService>().killAllChildProcesses()
    }

    override suspend fun search(
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<DownloadSong> {
        return manager.getService<IDownloadService>().search(query, title, artist, count)
    }
}
