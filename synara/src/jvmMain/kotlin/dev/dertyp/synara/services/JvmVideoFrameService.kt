package dev.dertyp.synara.services

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.kmpalette.palette.graphics.Palette
import dev.dertyp.synara.theme.getSeedsFromPalette
import dev.dertyp.synara.ui.components.VideoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.jetbrains.skia.ImageInfo
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class JvmVideoFrameService : VideoFrameService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val memoryCache = Collections.synchronizedMap(object : LinkedHashMap<String, VideoFrames>(15, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoFrames>): Boolean {
            return size > 10
        }
    })
    
    private val loadingStates = ConcurrentHashMap<String, MutableStateFlow<VideoFrames?>>()

    override fun getFrames(url: String, onLoaded: () -> Unit): StateFlow<VideoFrames?> {
        val cached = memoryCache[url]
        if (cached != null) {
            onLoaded()
            return MutableStateFlow(cached).asStateFlow()
        }

        return loadingStates.getOrPut(url) {
            MutableStateFlow<VideoFrames?>(null).also { stateFlow ->
                scope.launch {
                    try {
                        val videoFile = VideoCache.getFile(url)
                        val channel = NIOUtils.readableChannel(videoFile)
                        
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
                        val tempFrames = mutableListOf<Pair<Double, Bitmap>>()
                        val tempSeeds = mutableListOf<Pair<Double, Triple<Int?, Int?, Int?>>>()
                        val maxDecodeSize = 400

                        while (true) {
                            val frameWithMeta = grab.nativeFrameWithMetadata ?: break
                            val timestamp = frameWithMeta.timestamp
                            val frame = frameWithMeta.picture
                            
                            var bufferedImage = AWTUtil.toBufferedImage(frame)
                            if (bufferedImage.width > maxDecodeSize || bufferedImage.height > maxDecodeSize) {
                                val scale = maxDecodeSize.toDouble() / maxOf(bufferedImage.width, bufferedImage.height)
                                val scaled = BufferedImage((bufferedImage.width * scale).toInt(), (bufferedImage.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
                                val g = scaled.createGraphics()
                                g.drawRenderedImage(bufferedImage, AffineTransform.getScaleInstance(scale, scale))
                                g.dispose()
                                bufferedImage = scaled
                            }

                            val skiaBitmap = Bitmap().apply {
                                allocPixels(ImageInfo(bufferedImage.width, bufferedImage.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
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
                            
                            skiaBitmap.installPixels(rgba)
                            tempFrames.add(timestamp to skiaBitmap)

                            val composeBitmap = skiaBitmap.asComposeImageBitmap()
                            val palette = Palette.from(composeBitmap).generate()
                            tempSeeds.add(timestamp to getSeedsFromPalette(palette))
                        }
                        
                        val sortedEntries = tempFrames.zip(tempSeeds.map { it.second }).sortedBy { it.first.first }
                        val sortedBitmaps = sortedEntries.map { it.first.second.asComposeImageBitmap() }
                        val sortedSeeds = sortedEntries.map { it.second }
                        
                        val videoFrames = VideoFrames(sortedBitmaps, fps, sortedSeeds)
                        
                        memoryCache[url] = videoFrames
                        stateFlow.value = videoFrames
                        withContext(Dispatchers.Main) {
                            onLoaded()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        loadingStates.remove(url)
                    }
                }
            }
        }.asStateFlow()
    }

    override fun clearCache() {
        memoryCache.clear()
    }
}
