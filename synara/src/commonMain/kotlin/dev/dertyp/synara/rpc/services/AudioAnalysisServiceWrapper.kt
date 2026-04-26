package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongAudioData
import dev.dertyp.services.IAudioAnalysisService
import dev.dertyp.synara.rpc.RpcServiceManager

class AudioAnalysisServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IAudioAnalysisService {
    override suspend fun getAudioData(songId: PlatformUUID): SongAudioData? {
        return manager.getService<IAudioAnalysisService>().getAudioData(songId)
    }

    override suspend fun analyzeSong(songId: PlatformUUID) {
        manager.getService<IAudioAnalysisService>().analyzeSong(songId)
    }
}
