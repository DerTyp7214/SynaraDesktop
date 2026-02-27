package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformDate
import dev.dertyp.data.FavSync
import dev.dertyp.services.IFavSyncService
import dev.dertyp.services.ISyncService
import dev.dertyp.synara.rpc.RpcServiceManager

class FavSyncServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IFavSyncService {
    override suspend fun getLatestFavSync(service: ISyncService.SyncServiceType): FavSync? {
        return manager.getService<IFavSyncService>().getLatestFavSync(service)
    }

    override suspend fun insertFavSync(service: ISyncService.SyncServiceType, syncedAt: PlatformDate): Int {
        return manager.getService<IFavSyncService>().insertFavSync(service, syncedAt)
    }
}
