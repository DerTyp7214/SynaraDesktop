@file:JvmName("ImageLoaderCommon")
package dev.dertyp.synara.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Size
import dev.dertyp.PlatformUUID
import dev.dertyp.services.IImageService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Composable
fun rememberImageRequest(coverId: PlatformUUID?, size: Dp = 0.dp, builder: ImageRequest.Builder.() -> Unit = {}): ImageRequest? {
    val context = LocalPlatformContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    return remember(coverId, sizePx, builder) {
        ImageModel.withSize(context, coverId, sizePx, builder)
    }
}

fun setupCoil() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(getImageCacheDir(context).toPath())
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                add(SynaraImageFetcher.Factory())
                add(ImageModelKeyer())
            }
            .build()
    }
}

expect fun getImageCacheDir(context: PlatformContext): String

data class ImageModel(val id: PlatformUUID) {
    companion object {
        fun withSize(
            context: PlatformContext,
            imageId: PlatformUUID?,
            size: Int = 0,
            builder: ImageRequest.Builder.() -> Unit = {}
        ): ImageRequest? {
            if (imageId == null) return null
            return ImageRequest.Builder(context)
                .data(ImageModel(imageId))
                .size(if (size <= 0) Size.ORIGINAL else Size(size, size))
                .apply(builder)
                .build()
        }
    }
}

class ImageModelKeyer : Keyer<ImageModel> {
    val allowedSizes = listOf(64, 128, 256, 350, 475, 512, 696, 850, 1000, 1280, 1600, 2500)

    override fun key(data: ImageModel, options: Options): String {
        val sizePart = when (val size = options.size) {
            Size.ORIGINAL -> "original"
            else -> {
                val width = (size.width as? Dimension.Pixels)?.px ?: 0
                val height = (size.height as? Dimension.Pixels)?.px ?: 0
                val maxDim = maxOf(width, height)
                val targetSize = allowedSizes.firstOrNull { it >= maxDim } ?: "original"
                if (targetSize is Int) "${targetSize}x${targetSize}" else "original"
            }
        }
        return "${data.id}_${sizePart}"
    }
}

class SynaraImageFetcher(
    private val model: ImageModel,
    private val options: Options,
    private val imageService: IImageService,
    private val imageLoader: ImageLoader,
    private val keyer: ImageModelKeyer,
    private val networkSemaphore: Semaphore,
    private val inFlightRequests: MutableMap<String, Deferred<ByteArray?>>,
    private val inFlightMutex: Mutex
) : Fetcher {
    override suspend fun fetch(): FetchResult? = coroutineScope {
        val diskCache = imageLoader.diskCache

        val cacheKey = keyer.key(model, options)
        val originalKey = "${model.id}_original"

        var snapshot = diskCache?.openSnapshot(cacheKey)
        var readCacheKey = cacheKey

        if (snapshot == null) {
            val cacheKeyParts = cacheKey.substringAfter("${model.id}_")
            val requestedSize = cacheKeyParts.substringBefore("x").toIntOrNull()

            if (requestedSize != null) {
                val largerSizes = keyer.allowedSizes.filter { it > requestedSize }
                for (size in largerSizes) {
                    val largerKey = "${model.id}_${size}x${size}"
                    snapshot = diskCache?.openSnapshot(largerKey)
                    if (snapshot != null) {
                        readCacheKey = largerKey
                        break
                    }
                }
            }
        }

        if (snapshot == null) {
            snapshot = diskCache?.openSnapshot(originalKey)
            readCacheKey = originalKey
        }

        if (snapshot != null) {
            return@coroutineScope SourceFetchResult(
                source = ImageSource(
                    file = snapshot.data,
                    fileSystem = FileSystem.SYSTEM,
                    diskCacheKey = readCacheKey,
                    closeable = snapshot
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }

        val cacheKeyParts = cacheKey.substringAfter("${model.id}_")
        val requestedSize = if (cacheKeyParts == "original") 0 else cacheKeyParts.substringBefore("x").toIntOrNull() ?: 0

        val requestKey = "${model.id}_$requestedSize"
        val deferred = inFlightMutex.withLock {
            inFlightRequests.getOrPut(requestKey) {
                async {
                    try {
                        networkSemaphore.withPermit {
                            imageService.getImageData(model.id, requestedSize)
                        }
                    } finally {
                        @Suppress("DeferredResultUnused")
                        inFlightMutex.withLock {
                            inFlightRequests.remove(requestKey)
                        }
                    }
                }
            }
        }

        val data = deferred.await() ?: return@coroutineScope null

        if (data.isEmpty()) return@coroutineScope null

        diskCache?.openEditor(cacheKey)?.let { editor ->
            FileSystem.SYSTEM.write(editor.data) {
                write(data)
            }
            editor.commit()
        }

        val buffer = Buffer().apply { write(data) }

        SourceFetchResult(
            source = ImageSource(
                source = buffer,
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory : Fetcher.Factory<ImageModel>, KoinComponent {
        private val imageService: IImageService by inject()
        private val keyer = ImageModelKeyer()
        private val networkSemaphore = Semaphore(6)
        private val inFlightRequests = mutableMapOf<String, Deferred<ByteArray?>>()
        private val inFlightMutex = Mutex()

        override fun create(data: ImageModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return SynaraImageFetcher(
                data,
                options,
                imageService,
                imageLoader,
                keyer,
                networkSemaphore,
                inFlightRequests,
                inFlightMutex
            )
        }
    }
}
