package dev.dertyp.synara.rpc.services

import dev.dertyp.services.tdn.*
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

    override suspend fun downloadTidalIds(ids: List<String>, type: Type) {
        manager.getService<IDownloadService>().downloadTidalIds(ids, type)
    }

    override suspend fun existsByTidalId(id: String, type: Type): Boolean {
        return manager.getService<IDownloadService>().existsByTidalId(id, type)
    }

    override suspend fun setTidalDownloadService(service: TidalDownloadService) {
        manager.getService<IDownloadService>().setTidalDownloadService(service)
    }

    override suspend fun getTidalDownloadService(): TidalDownloadService {
        return manager.getService<IDownloadService>().getTidalDownloadService()
    }

    override suspend fun tidalDownloadAuthorized(): Boolean {
        return manager.getService<IDownloadService>().tidalDownloadAuthorized()
    }

    override fun tidalDownloadLogin(): Flow<String> {
        return manager.getService<IDownloadService>().tidalDownloadLogin()
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

    override suspend fun searchTidal(
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<TidalSong> {
        return manager.getService<IDownloadService>().searchTidal(query, title, artist, count)
    }
}
