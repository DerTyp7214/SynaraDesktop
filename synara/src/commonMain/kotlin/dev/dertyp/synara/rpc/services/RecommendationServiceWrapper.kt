package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.MoodSummary
import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.UserSong
import dev.dertyp.services.IRecommendationService
import dev.dertyp.synara.rpc.RpcServiceManager

class RecommendationServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IRecommendationService {
    override suspend fun getSimilarSongs(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return manager.getService<IRecommendationService>().getSimilarSongs(seedSongIds, limit)
    }

    override suspend fun getMix(window: RecommendationWindow, limit: Int): List<UserSong> {
        return manager.getService<IRecommendationService>().getMix(window, limit)
    }

    override suspend fun getMoodPlaylist(mood: String, limit: Int): List<UserSong> {
        return manager.getService<IRecommendationService>().getMoodPlaylist(mood, limit)
    }

    override suspend fun getMoods(): List<MoodSummary> {
        return manager.getService<IRecommendationService>().getMoods()
    }
}
