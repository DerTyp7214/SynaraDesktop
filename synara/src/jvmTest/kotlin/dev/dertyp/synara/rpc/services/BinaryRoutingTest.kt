package dev.dertyp.synara.rpc.services

import dev.dertyp.PlatformUUID
import dev.dertyp.services.IAnimatedImageService
import dev.dertyp.services.IImageService
import dev.dertyp.services.IReleaseService
import dev.dertyp.synara.rpc.RpcServiceManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.RpcClient
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BinaryRoutingTest {

    private val imageClient = mockk<RpcClient>()
    private val manager = mockk<RpcServiceManager> {
        every { this@mockk.imageClient } returns this@BinaryRoutingTest.imageClient
    }

    @Test
    fun imageDataUsesTheImageClient() = runTest {
        val id = PlatformUUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        val service = mockk<IImageService>()
        every { manager.getService(IImageService::class, imageClient) } returns service
        coEvery { service.getImageData(id, 256) } returns bytes

        assertContentEquals(bytes, ImageServiceWrapper(manager).getImageData(id, 256))

        verify(exactly = 1) { manager.getService(IImageService::class, imageClient) }
        verify(exactly = 0) { manager.getService(IImageService::class) }
    }

    @Test
    fun releaseImageUsesTheImageClient() = runTest {
        val id = PlatformUUID.randomUUID()
        val bytes = byteArrayOf(4, 5)
        val service = mockk<IReleaseService>()
        every { manager.getService(IReleaseService::class, imageClient) } returns service
        coEvery { service.getReleaseImage(id, 128) } returns bytes

        assertContentEquals(bytes, ReleaseServiceWrapper(manager).getReleaseImage(id, 128))

        verify(exactly = 1) { manager.getService(IReleaseService::class, imageClient) }
        verify(exactly = 0) { manager.getService(IReleaseService::class) }
    }

    @Test
    fun animatedImageClosesTheDedicatedClientAfterSuccess() = runTest {
        val id = PlatformUUID.randomUUID()
        val bytes = byteArrayOf(7)
        val dedicatedClient = mockk<KtorRpcClient>()
        every { dedicatedClient.close(any()) } just runs
        val service = mockk<IAnimatedImageService>()
        coEvery { manager.getDedicatedClient() } returns dedicatedClient
        every { manager.getService(IAnimatedImageService::class, dedicatedClient) } returns service
        coEvery { service.getAnimatedImageData(id) } returns bytes

        assertContentEquals(bytes, AnimatedImageServiceWrapper(manager).getAnimatedImageData(id))

        verify(exactly = 1) { dedicatedClient.close(any()) }
    }

    @Test
    fun animatedImageClosesTheDedicatedClientWhenTheCallThrows() = runTest {
        val id = PlatformUUID.randomUUID()
        val dedicatedClient = mockk<KtorRpcClient>()
        every { dedicatedClient.close(any()) } just runs
        val service = mockk<IAnimatedImageService>()
        coEvery { manager.getDedicatedClient() } returns dedicatedClient
        every { manager.getService(IAnimatedImageService::class, dedicatedClient) } returns service
        coEvery { service.getAnimatedImageData(id) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { AnimatedImageServiceWrapper(manager).getAnimatedImageData(id) }

        verify(exactly = 1) { dedicatedClient.close(any()) }
    }
}
