@file:UseContextualSerialization(PlatformUUID::class)

package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.AnimatedImage
import dev.dertyp.data.InsertableAnimatedImage
import dev.dertyp.services.IAnimatedImageService
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.serialization.UseContextualSerialization

class AnimatedImageServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), IAnimatedImageService {
    override suspend fun byId(id: PlatformUUID): AnimatedImage? =
        manager.getService<IAnimatedImageService>().byId(id)

    override suspend fun byHash(hash: String): AnimatedImage? =
        manager.getService<IAnimatedImageService>().byHash(hash)

    override suspend fun getCoverHashes(hashes: List<String>): Map<String, PlatformUUID> =
        manager.getService<IAnimatedImageService>().getCoverHashes(hashes)

    override suspend fun getAnimatedImageData(id: PlatformUUID): ByteArray? =
        manager.getService<IAnimatedImageService>().getAnimatedImageData(id)

    override suspend fun createAnimatedImage(bytes: ByteArray, origin: String): PlatformUUID =
        manager.getService<IAnimatedImageService>().createAnimatedImage(bytes, origin)

    override suspend fun createBatch(images: List<InsertableAnimatedImage>): Map<String, PlatformUUID> =
        manager.getService<IAnimatedImageService>().createBatch(images)
}
