package dev.dertyp.synara.services

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.dertyp.logging.Logger
import dev.dertyp.synara.ui.components.SynaraVideoPlayer
import dev.dertyp.synara.utils.SynaraDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jetbrains.skia.Bitmap
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmVideoFrameServiceTest {
    private object UnconfinedDispatchers : SynaraDispatchers {
        override val worker: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private lateinit var service: VideoFrameService

    @BeforeTest
    fun setUp() {
        service = startKoin {
            modules(
                module {
                    single<LocalStorageService> { storage }
                    single<SynaraDispatchers> { UnconfinedDispatchers }
                    single<Logger> { mockk(relaxed = true) }
                    singleOf(::JvmVideoFrameService) bind VideoFrameService::class
                }
            )
        }.koin.get()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        val videos = File(cacheRoot, "videos")
        videos.deleteOnExit()
        videos.listFiles()?.forEach { it.delete() }
    }

    private fun residentMegabytes(): Long? {
        val status = File("/proc/self/status")
        if (!status.exists()) return null
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val line = status.readLines().first { it.startsWith("VmRSS:") }
        return line.split(Regex("\\s+"))[1].toLong() / 1024
    }

    private fun video(size: Int, frames: Int): ByteArray {
        val file = File.createTempFile("cover", ".mp4", cacheRoot)
        try {
            val encoder = AWTSequenceEncoder.createSequenceEncoder(file, 30)
            repeat(frames) { index ->
                val image = BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR)
                val graphics = image.createGraphics()
                graphics.color = Color.getHSBColor(index / frames.toFloat(), 0.8f, 0.9f)
                graphics.fillRect(0, 0, size, size)
                graphics.color = Color.WHITE
                graphics.fillRect((index * 3) % size, size / 4, size / 4, size / 2)
                graphics.dispose()
                encoder.encodeImage(image)
            }
            encoder.finish()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun load(key: String, bytes: ByteArray): VideoFrames {
        var loaded = false
        val frames = service.getFrames(key, { bytes }) { loaded = true }.value
        assertTrue(loaded, "$key did not report loaded")
        return assertNotNull(frames, "$key has no frames")
    }

    @Test
    fun fiveCoversStayCachedAsEncodedFrames() {
        val video = video(size = 64, frames = 120)
        val before = residentMegabytes()
        val covers = (1..5).associate { "cover-$it" to load("cover-$it", video) }
        val after = residentMegabytes()

        covers.values.forEach { cover ->
            assertEquals(120, cover.frames.size)
            assertEquals(cover.frames.size, cover.seeds.size)
            assertTrue(cover.frames.all { it.width == 64 && it.height == 64 })
            assertTrue(cover.seeds.chunked(6).all { stride -> stride.all { it == stride.first() } })
            assertEquals(30f, cover.fps)
        }
        var reloads = 0
        covers.forEach { (key, cover) ->
            val cached = service.getFrames(key, { reloads++; video }) {}.value
            assertSame(cover, cached)
        }
        assertEquals(0, reloads)

        assumeTrue(before != null && after != null)
        val grown = after!! - before!!
        println("Video frames native memory: rss $before -> $after MB over 5 covers")
        assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
    }

    @Test
    fun leastRecentlyUsedCoverIsDecodedAgainAfterTenOthers() {
        val video = video(size = 64, frames = 12)
        (1..10).forEach { load("lru-$it", video) }
        val first = load("lru-1", video)
        load("lru-11", video)

        var reloads = 0
        assertSame(first, service.getFrames("lru-1", { reloads++; video }) {}.value)
        assertEquals(0, reloads)
        assertNotNull(service.getFrames("lru-2", { reloads++; video }) {}.value)
        assertEquals(1, reloads)
    }

    @Test
    fun drawingTwoLoopsKeepsNativeMemoryFlat() {
        val video = video(size = 400, frames = SOAK_FRAMES)
        val index = mutableIntStateOf(0)
        var loaded = false
        val scene = ImageComposeScene(320, 240, Density(2f)) {
            SynaraVideoPlayer(
                key = "soak",
                loader = { video },
                modifier = Modifier.fillMaxSize(),
                onLoaded = { loaded = true },
                frameIndex = index.intValue
            )
        }
        try {
            var time = 0L
            fun loop() = repeat(SOAK_FRAMES) { frame ->
                index.intValue = frame
                time += 16_000_000L
                scene.render(time).close()
            }
            loop()
            assertTrue(loaded, "cover did not load")
            assertEquals(SOAK_FRAMES, load("soak", video).frames.size)
            val center = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { it.getColor(160, 120) } }
            assertEquals(0xFF, center ushr 24, "frame not drawn")

            val before = residentMegabytes()
            loop()
            val after = residentMegabytes()
            assumeTrue(before != null && after != null)
            val grown = after!! - before!!
            println("Video player native memory: rss $before -> $after MB over $SOAK_FRAMES frames")
            assertTrue(grown < MAX_GROWTH_MB, "native memory grew by $grown MB")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val SOAK_FRAMES = 300
        const val MAX_GROWTH_MB = 64L

        val cacheRoot: File = Files.createTempDirectory("synara-video-frames").toFile().also { it.deleteOnExit() }
        val storage = mockk<LocalStorageService> {
            every { getCacheDir() } returns cacheRoot.path
        }
    }
}
