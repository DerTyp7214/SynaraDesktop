package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.PlaybackState
import dev.dertyp.services.IPlaybackService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

class PlaybackServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IPlaybackService {
    override suspend fun getPlaybackState(sessionId: PlatformUUID): PlaybackState? {
        return manager.getService<IPlaybackService>().getPlaybackState(sessionId)
    }

    override suspend fun setPlaybackState(sessionId: PlatformUUID, state: PlaybackState): Boolean {
        return manager.getService<IPlaybackService>().setPlaybackState(sessionId, state)
    }

    override fun observePlaybackState(sessionId: PlatformUUID): Flow<PlaybackState> {
        return manager.getService<IPlaybackService>().observePlaybackState(sessionId)
    }
}
