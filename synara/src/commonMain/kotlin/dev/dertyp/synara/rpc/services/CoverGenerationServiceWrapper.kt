package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.CoverGenerationOptions
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverInfo
import dev.dertyp.data.CoverTarget
import dev.dertyp.services.ICoverGenerationService
import dev.dertyp.synara.rpc.RpcServiceManager

class CoverGenerationServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ICoverGenerationService {
    override suspend fun options(): CoverGenerationOptions {
        return manager.getService<ICoverGenerationService>().options()
    }

    override suspend fun coverInfo(target: CoverTarget): CoverInfo {
        return manager.getService<ICoverGenerationService>().coverInfo(target)
    }

    override suspend fun previewCoverImage(target: CoverTarget, params: CoverGenerationParams): ByteArray {
        return manager.getService<ICoverGenerationService>().previewCoverImage(target, params)
    }

    override suspend fun applyCover(target: CoverTarget, params: CoverGenerationParams): PlatformUUID {
        return manager.getService<ICoverGenerationService>().applyCover(target, params)
    }

    override suspend fun resetCover(target: CoverTarget): Boolean {
        return manager.getService<ICoverGenerationService>().resetCover(target)
    }

    override suspend fun generateMissing(params: CoverGenerationParams): PlatformUUID {
        return manager.getService<ICoverGenerationService>().generateMissing(params)
    }
}
