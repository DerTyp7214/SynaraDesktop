package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.MosaicGenerationResponse
import dev.dertyp.services.IImageService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.flow.Flow

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

    override suspend fun createBatch(images: List<InsertableImage>): Map<String, PlatformUUID> {
        return manager.getService<IImageService>().createBatch(images)
    }

    override suspend fun moveImages(oldPath: String, newPath: String): Int {
        return manager.getService<IImageService>().moveImages(oldPath, newPath)
    }

    override fun generateMosaicImage(
        image: ByteArray,
        width: Int,
        height: Int,
        resultSize: Int
    ): Flow<MosaicGenerationResponse> {
        return manager.getService<IImageService>().generateMosaicImage(image, width, height, resultSize)
    }
}
