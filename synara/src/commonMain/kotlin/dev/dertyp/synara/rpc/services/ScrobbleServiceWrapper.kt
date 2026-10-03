package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.services.IScrobbleService
import dev.dertyp.synara.rpc.RpcServiceManager

class ScrobbleServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IScrobbleService {
    override suspend fun nowPlaying(songId: PlatformUUID) {
        manager.getService<IScrobbleService>().nowPlaying(songId)
    }

    override suspend fun reportPlayback(report: PlaybackReport): Long {
        return manager.getService<IScrobbleService>().reportPlayback(report)
    }

    override suspend fun clearNowPlaying() {
        manager.getService<IScrobbleService>().clearNowPlaying()
    }

    override suspend fun listened(request: ScrobbleRequest) {
        manager.getService<IScrobbleService>().listened(request)
    }

    override suspend fun recentListens(limit: Int): RecentListens {
        return manager.getService<IScrobbleService>().recentListens(limit)
    }

    override suspend fun recentArtists(limit: Int): List<ListenedArtist> {
        return manager.getService<IScrobbleService>().recentArtists(limit)
    }

    override suspend fun recentAlbums(limit: Int): List<ListenedAlbum> {
        return manager.getService<IScrobbleService>().recentAlbums(limit)
    }
}
