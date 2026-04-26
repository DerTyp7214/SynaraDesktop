package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.UserSong
import dev.dertyp.services.IDiscoveryService
import dev.dertyp.synara.rpc.RpcServiceManager

class DiscoveryServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IDiscoveryService {
    override suspend fun getSimilarSongs(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSimilarSongs(seedSongIds, limit)
    }

    override suspend fun getSimilarSongsByPlaylist(playlistId: PlatformUUID, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSimilarSongsByPlaylist(playlistId, limit)
    }

    override suspend fun getSimilarSongsByBpm(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSimilarSongsByBpm(seedSongIds, limit)
    }

    override suspend fun getSimilarSongsByEnergy(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSimilarSongsByEnergy(seedSongIds, limit)
    }

    override suspend fun getSimilarSongsByMood(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSimilarSongsByMood(seedSongIds, limit)
    }

    override suspend fun getSongsBySameComposers(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSongsBySameComposers(seedSongIds, limit)
    }

    override suspend fun getSongsBySameLyricists(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSongsBySameLyricists(seedSongIds, limit)
    }

    override suspend fun getSongsBySameProducers(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IDiscoveryService>().getSongsBySameProducers(seedSongIds, limit)
    }
}
