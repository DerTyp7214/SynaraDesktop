package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.CustomMetadata
import dev.dertyp.services.ICustomAudioService
import dev.dertyp.synara.rpc.RpcServiceManager

class CustomAudioServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ICustomAudioService {
    override suspend fun uploadCustomAudio(
        fileData: ByteArray,
        fileName: String,
        metadata: CustomMetadata?
    ): PlatformUUID? {
        return manager.getService<ICustomAudioService>().uploadCustomAudio(fileData, fileName, metadata)
    }
}
