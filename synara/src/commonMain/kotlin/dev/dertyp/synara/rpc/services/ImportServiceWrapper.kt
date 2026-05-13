package dev.dertyp.synara.rpc.services

import dev.dertyp.services.import.*
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class ImportServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IImportService {
    override fun logs(): Flow<LogLine> {
        return manager.getService<IImportService>().logs()
    }

    override suspend fun currentImport(): ImportQueueEntry? {
        return manager.getService<IImportService>().currentImport()
    }

    override suspend fun importQueue(): List<ImportQueueEntry> {
        return manager.getService<IImportService>().importQueue()
    }

    override suspend fun finishedImports(): List<FinishedImportQueueEntry> {
        return manager.getService<IImportService>().finishedImports()
    }

    override suspend fun syncFavouritesAvailable(): Boolean {
        return manager.getService<IImportService>().syncFavouritesAvailable()
    }

    override suspend fun syncFavourites() {
        manager.getService<IImportService>().syncFavourites()
    }

    override suspend fun importIds(ids: List<PrefixedId>, type: Type, importer: ImportBackend?) {
        manager.getService<IImportService>().importIds(ids, type, importer)
    }

    override suspend fun importUrls(urls: List<String>) {
        manager.getService<IImportService>().importUrls(urls)
    }

    override suspend fun getImporterForUrl(url: String): ImportBackend? {
        return manager.getService<IImportService>().getImporterForUrl(url)
    }

    override suspend fun existsByOriginalId(id: PrefixedId, type: Type): Boolean {
        return manager.getService<IImportService>().existsByOriginalId(id, type)
    }

    override suspend fun setImportService(service: ImportBackend) {
        manager.getService<IImportService>().setImportService(service)
    }

    override suspend fun getImportService(): ImportBackend {
        return manager.getService<IImportService>().getImportService()
    }

    override suspend fun getAllImportServices(): List<ImportBackend> {
        return manager.getService<IImportService>().getAllImportServices()
    }

    override suspend fun importAuthorized(): Boolean {
        return manager.getService<IImportService>().importAuthorized()
    }

    override fun importLogin(): Flow<String> {
        return manager.getService<IImportService>().importLogin()
    }

    override suspend fun tidalSyncAuthorized(): Boolean {
        return manager.getService<IImportService>().tidalSyncAuthorized()
    }

    override suspend fun getAuthUrl(): String {
        return manager.getService<IImportService>().getAuthUrl()
    }

    override suspend fun killAllChildProcesses() {
        manager.getService<IImportService>().killAllChildProcesses()
    }

    override suspend fun search(
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<ImportSong> {
        return manager.getService<IImportService>().search(query, title, artist, count)
    }
}
