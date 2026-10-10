package dev.dertyp.synara.services

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.kmpalette.palette.graphics.Palette
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.theme.getSeedsFromPalette
import dev.dertyp.synara.ui.components.VideoCache
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.jcodec.scale.AWTUtil
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class JvmVideoFrameService(
    private val dispatchers: SynaraDispatchers,
    private val logger: Logger
) : VideoFrameService {
    private val scope = CoroutineScope(dispatchers.worker + SupervisorJob())
    private val memoryCache = Collections.synchronizedMap(object : LinkedHashMap<String, VideoFrames>(15, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoFrames>): Boolean {
            return size > 10
        }
    })

    private val loadingStates = ConcurrentHashMap<String, MutableStateFlow<VideoFrames?>>()

    override fun getFrames(key: String, loader: suspend () -> ByteArray?, onLoaded: () -> Unit): StateFlow<VideoFrames?> {
        val cached = memoryCache[key]
        if (cached != null) {
            onLoaded()
            return MutableStateFlow(cached).asStateFlow()
        }

        val stateFlow = MutableStateFlow<VideoFrames?>(null)
        val loading = loadingStates.putIfAbsent(key, stateFlow)
        if (loading != null) return loading.asStateFlow()

        scope.launch {
            try {
                val bytes = loader()
                val videoFile = VideoCache.getFileFromBytes(key, bytes) ?: return@launch
                val videoFrames = decode(videoFile)

                memoryCache[key] = videoFrames
                stateFlow.value = videoFrames
                withContext(dispatchers.main) {
                    onLoaded()
                }
            } catch (e: Exception) {
                logger.error(LogTag("video"), "Failed to decode animated cover $key", e)
            } finally {
                loadingStates.remove(key)
            }
        }
        return stateFlow.asStateFlow()
    }

    private fun decode(videoFile: File): VideoFrames = NIOUtils.readableChannel(videoFile).use { channel ->
        var fps = 25f
        try {
            val demuxer = MP4Demuxer.createMP4Demuxer(channel)
            val track = demuxer.videoTrack
            if (track != null) {
                val meta = track.meta
                if (meta.totalDuration > 0) {
                    fps = (meta.totalFrames.toDouble() / meta.totalDuration).toFloat().coerceIn(1f, 120f)
                }
            }
        } catch (_: Exception) {}

        channel.setPosition(0)
        val grab = FrameGrab.createFrameGrab(channel)
        val decoded = mutableListOf<DecodedFrame>()

        while (true) {
            val frameWithMeta = grab.nativeFrameWithMetadata ?: break
            val timestamp = frameWithMeta.timestamp
            val frame = frameWithMeta.picture

            var bufferedImage = AWTUtil.toBufferedImage(frame)
            if (bufferedImage.width > MAX_DECODE_SIZE || bufferedImage.height > MAX_DECODE_SIZE) {
                val scale = MAX_DECODE_SIZE.toDouble() / maxOf(bufferedImage.width, bufferedImage.height)
                val scaled = BufferedImage(
                    (bufferedImage.width * scale).toInt(),
                    (bufferedImage.height * scale).toInt(),
                    BufferedImage.TYPE_INT_ARGB
                )
                val g = scaled.createGraphics()
                g.drawRenderedImage(bufferedImage, AffineTransform.getScaleInstance(scale, scale))
                g.dispose()
                bufferedImage = scaled
            }

            val argb = bufferedImage.getRGB(0, 0, bufferedImage.width, bufferedImage.height, null, 0, bufferedImage.width)
            val rgba = ByteArray(bufferedImage.width * bufferedImage.height * 4)
            for (i in argb.indices) {
                val p = argb[i]
                rgba[i * 4 + 0] = (p shr 16 and 0xFF).toByte()
                rgba[i * 4 + 1] = (p shr 8 and 0xFF).toByte()
                rgba[i * 4 + 2] = (p and 0xFF).toByte()
                rgba[i * 4 + 3] = (p shr 24 and 0xFF).toByte()
            }

            val skiaBitmap = Bitmap()
            try {
                skiaBitmap.allocPixels(ImageInfo(bufferedImage.width, bufferedImage.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
                skiaBitmap.installPixels(rgba)

                val seeds = if (decoded.size % PALETTE_STRIDE == 0) {
                    getSeedsFromPalette(Palette.from(skiaBitmap.asComposeImageBitmap()).generate())
                } else {
                    null
                }
                val encoded = Image.makeFromBitmap(skiaBitmap).use { raw ->
                    checkNotNull(raw.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)) { "Failed to encode frame" }.use { it.bytes }
                }
                decoded.add(DecodedFrame(timestamp, Image.makeFromEncoded(encoded), seeds))
            } finally {
                skiaBitmap.close()
            }
        }

        val sorted = decoded.sortedBy { it.timestamp }
        var lastSeeds = sorted.firstNotNullOfOrNull { it.seeds } ?: Triple(null, null, null)
        VideoFrames(
            frames = sorted.map { it.image },
            fps = fps,
            seeds = sorted.map { frame -> frame.seeds?.also { lastSeeds = it } ?: lastSeeds }
        )
    }

    override fun clearCache() {
        memoryCache.clear()
    }

    private class DecodedFrame(val timestamp: Double, val image: Image, val seeds: Triple<Int?, Int?, Int?>?)

    private companion object {
        const val MAX_DECODE_SIZE = 400
        const val JPEG_QUALITY = 90
        const val PALETTE_STRIDE = 6
    }
}
