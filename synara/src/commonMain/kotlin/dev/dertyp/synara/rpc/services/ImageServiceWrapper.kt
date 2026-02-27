package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Image
import dev.dertyp.services.IImageService
import dev.dertyp.synara.rpc.RpcServiceManager

class ImageServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IImageService {
    override suspend fun byId(id: PlatformUUID): Image? {
        return manager.getService<IImageService>().byId(id)
    }

    override suspend fun byHash(hash: String): Image? {
        return manager.getService<IImageService>().byHash(hash)
    }

    override suspend fun getCoverHashes(hashes: List<String>): Map<String, PlatformUUID> {
        return manager.getService<IImageService>().getCoverHashes(hashes)
    }

    override suspend fun getImageData(id: PlatformUUID, size: Int): ByteArray? {
        return manager.getService<IImageService>().getImageData(id, size)
    }

    override suspend fun createImage(bytes: ByteArray, origin: String): PlatformUUID {
        return manager.getService<IImageService>().createImage(bytes, origin)
    }
}
