package dev.dertyp.synara.player

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RepeatMode
import dev.dertyp.randomPlatformUUID
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.services.LocalStorageService
import dev.dertyp.synara.testing.TempStorageService
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.types.Variant
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MprisObjectImplTest {

    private companion object {
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
        const val TRACK_PREFIX = "/org/mpris/MediaPlayer2/track/"
    }

    private val playingFlow = MutableStateFlow(false)
    private val repeatFlow = MutableStateFlow(RepeatMode.OFF)
    private val shuffleFlow = MutableStateFlow(false)
    private val volumeFlow = MutableStateFlow(1f)
    private val rateFlow = MutableStateFlow(1.0)
    private val metadataFlow = MutableStateFlow<NowPlayingMetadata?>(null)
    private val bridge = mockk<MediaControlBridge>(relaxed = true)
    private val dir: Path = Files.createTempDirectory("synara-mpris-test")
    private lateinit var mpris: MprisObjectImpl

    @BeforeTest
    fun setUp() {
        stopTestKoin()
        every { bridge.isPlaying } returns playingFlow
        every { bridge.repeatMode } returns repeatFlow
        every { bridge.shuffleMode } returns shuffleFlow
        every { bridge.volume } returns volumeFlow
        every { bridge.rate } returns rateFlow
        every { bridge.metadata } returns metadataFlow
        every { bridge.minimumRate } returns 1.0
        every { bridge.maximumRate } returns 1.0
        every { bridge.position() } returns 0L
        mpris = MprisObjectImpl(bridge)
    }

    @AfterTest
    fun tearDown() {
        stopTestKoin()
        dir.toFile().deleteRecursively()
    }

    private fun nowPlaying(
        trackId: String = "track",
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        durationMs: Long = 1000,
        imageId: PlatformUUID? = null
    ) = NowPlayingMetadata(
        trackId = trackId,
        title = "Title",
        artists = artists,
        artist = artists.joinToString(", "),
        album = album,
        durationMs = durationMs,
        imageId = imageId,
        isPodcast = false
    )

    private fun startKoinWithServer(host: String?, port: Int?, ssl: Boolean, sslOverride: Boolean?) {
        val manager = mockk<RpcServiceManager>()
        every { manager.host } returns host
        every { manager.port } returns port
        every { manager.ssl } returns ssl
        every { manager.rpcPath } returns "/"
        every { manager.sessionSslOverride } returns MutableStateFlow(sslOverride)
        startTestKoin(
            module {
                single { manager }
                single<LocalStorageService> { TempStorageService(dir) }
            }
        )
    }

    private fun declaredProperties(type: Class<*>): Set<String> =
        type.getAnnotationsByType(DBusProperty::class.java).map { it.name }.toSet()

    @Test
    fun `Seek converts microseconds to a millisecond offset`() {
        mpris.Seek(2_500_000)

        verify { bridge.seekBy(2500) }
    }

    @Test
    fun `SetPosition on the current track converts microseconds to a millisecond position`() {
        metadataFlow.value = nowPlaying(trackId = "abc-def")

        mpris.SetPosition(DBusPath("${TRACK_PREFIX}abc_def"), 3_000_000)

        verify { bridge.seekTo(3000) }
    }

    @Test
    fun `SetPosition on a foreign track does not seek`() {
        metadataFlow.value = nowPlaying(trackId = "abc-def")

        mpris.SetPosition(DBusPath("${TRACK_PREFIX}other"), 3_000_000)

        verify(exactly = 0) { bridge.seekTo(any()) }
    }

    @Test
    fun `SetPosition without a current track does not seek`() {
        mpris.SetPosition(DBusPath("${TRACK_PREFIX}abc_def"), 3_000_000)

        verify(exactly = 0) { bridge.seekTo(any()) }
    }

    @Test
    fun `SetPosition with the no track path does not seek while no track is loaded`() {
        metadataFlow.value = null

        mpris.SetPosition(DBusPath("${TRACK_PREFIX}no_track"), 3_000_000)

        verify(exactly = 0) { bridge.seekTo(any()) }
    }

    @Test
    fun `Position reports the bridge position in microseconds`() {
        every { bridge.position() } returns 1500L

        assertEquals(1_500_000L, mpris.getPosition())
    }

    @Test
    fun `LoopStatus maps every repeat mode to its MPRIS value`() {
        repeatFlow.value = RepeatMode.OFF
        assertEquals("None", mpris.getLoopStatus())
        repeatFlow.value = RepeatMode.ALL
        assertEquals("Playlist", mpris.getLoopStatus())
        repeatFlow.value = RepeatMode.ONE
        assertEquals("Track", mpris.getLoopStatus())
    }

    @Test
    fun `setLoopStatus maps the three MPRIS values to repeat modes`() {
        mpris.setLoopStatus("None")
        mpris.setLoopStatus("Playlist")
        mpris.setLoopStatus("Track")

        verify(exactly = 1) { bridge.setRepeatMode(RepeatMode.OFF) }
        verify(exactly = 1) { bridge.setRepeatMode(RepeatMode.ALL) }
        verify(exactly = 1) { bridge.setRepeatMode(RepeatMode.ONE) }
    }

    @Test
    fun `setLoopStatus with an unknown value does not touch the bridge`() {
        mpris.setLoopStatus("Sideways")

        verify(exactly = 0) { bridge.setRepeatMode(any()) }
    }

    @Test
    fun `PlaybackStatus follows the playing flag while a track is loaded`() {
        metadataFlow.value = nowPlaying()
        playingFlow.value = true
        assertEquals("Playing", mpris.getPlaybackStatus())
        playingFlow.value = false
        assertEquals("Paused", mpris.getPlaybackStatus())
    }

    @Test
    fun `PlaybackStatus is Stopped without a track while nothing plays`() {
        playingFlow.value = false
        assertEquals("Stopped", mpris.getPlaybackStatus())
    }

    @Test
    fun `PlaybackStatus is Playing while audio plays and the track is not known yet`() {
        playingFlow.value = true
        assertEquals("Playing", mpris.getPlaybackStatus())
    }

    @Test
    fun `Get PlaybackStatus returns the same value as the property`() {
        metadataFlow.value = nowPlaying()
        playingFlow.value = true
        assertEquals("Playing", mpris.Get<String>(PLAYER, "PlaybackStatus"))
        playingFlow.value = false
        metadataFlow.value = null
        assertEquals("Stopped", mpris.Get<String>(PLAYER, "PlaybackStatus"))
    }

    @Test
    fun `Set Volume forwards the value as a float`() {
        mpris.Set(PLAYER, "Volume", Variant(0.5))

        verify { bridge.setVolume(0.5f) }
    }

    @Test
    fun `Set Shuffle forwards the flag`() {
        mpris.Set(PLAYER, "Shuffle", Variant(true))

        verify { bridge.setShuffle(true) }
    }

    @Test
    fun `Set Rate forwards the value`() {
        mpris.Set(PLAYER, "Rate", Variant(1.5))

        verify { bridge.setRate(1.5) }
    }

    @Test
    fun `Set LoopStatus forwards the mapped repeat mode`() {
        mpris.Set(PLAYER, "LoopStatus", Variant("Track"))

        verify { bridge.setRepeatMode(RepeatMode.ONE) }
    }

    @Test
    fun `Set with an unknown property or interface does not touch the bridge`() {
        mpris.Set(PLAYER, "Nothing", Variant(true))
        mpris.Set("org.example.Other", "Volume", Variant(0.5))

        verify(exactly = 0) { bridge.setVolume(any()) }
        verify(exactly = 0) { bridge.setShuffle(any()) }
    }

    @Test
    fun `Get with an unknown interface fails`() {
        assertFailsWith<IllegalArgumentException> { mpris.Get<Any>("org.example.Other", "Volume") }
    }

    @Test
    fun `Get with an unknown property fails on both interfaces`() {
        assertFailsWith<IllegalArgumentException> { mpris.Get<Any>(ROOT, "Nothing") }
        assertFailsWith<IllegalArgumentException> { mpris.Get<Any>(PLAYER, "Nothing") }
    }

    @Test
    fun `Get answers every declared property of both interfaces`() {
        declaredProperties(IMprisMediaPlayer2::class.java).forEach { name ->
            assertNotNull(mpris.Get<Any>(ROOT, name), name)
        }
        declaredProperties(IMprisMediaPlayer2Player::class.java).forEach { name ->
            assertNotNull(mpris.Get<Any>(PLAYER, name), name)
        }
    }

    @Test
    fun `Get returns the live player values`() {
        playingFlow.value = true
        volumeFlow.value = 0.25f
        shuffleFlow.value = true

        assertEquals("Playing", mpris.Get<String>(PLAYER, "PlaybackStatus"))
        assertEquals(0.25, mpris.Get<Double>(PLAYER, "Volume"))
        assertEquals(true, mpris.Get<Boolean>(PLAYER, "Shuffle"))
    }

    @Test
    fun `GetAll keys equal the declared D-Bus properties of the root interface`() {
        assertEquals(declaredProperties(IMprisMediaPlayer2::class.java), mpris.GetAll(ROOT).keys)
    }

    @Test
    fun `GetAll keys equal the declared D-Bus properties of the player interface`() {
        assertEquals(declaredProperties(IMprisMediaPlayer2Player::class.java), mpris.GetAll(PLAYER).keys)
    }

    @Test
    fun `GetAll for an unknown interface is empty`() {
        assertTrue(mpris.GetAll("org.example.Other").isEmpty())
    }

    @Test
    fun `GetAll declares Metadata as a dictionary of variants`() {
        val metadata = assertNotNull(mpris.GetAll(PLAYER)["Metadata"])

        assertEquals("a{sv}", metadata.sig)
    }

    @Test
    fun `Metadata without a track holds only the no_track path`() {
        metadataFlow.value = null

        val metadata = mpris.getMetadata()

        assertEquals(setOf("mpris:trackid"), metadata.keys)
        assertEquals("${TRACK_PREFIX}no_track", assertIs<DBusPath>(metadata.getValue("mpris:trackid").value).path)
    }

    @Test
    fun `Metadata maps a track with several artists and no album`() {
        metadataFlow.value = nowPlaying(trackId = "a-b-c", artists = listOf("First", "Second"), album = null, durationMs = 1000)

        val metadata = mpris.getMetadata()

        assertEquals("${TRACK_PREFIX}a_b_c", assertIs<DBusPath>(metadata.getValue("mpris:trackid").value).path)
        assertEquals(1_000_000L, metadata.getValue("mpris:length").value)
        assertEquals("Title", metadata.getValue("xesam:title").value)
        val artists = metadata.getValue("xesam:artist")
        assertEquals("as", artists.sig)
        assertEquals(listOf("First", "Second"), assertIs<Array<*>>(artists.value).toList())
        assertFalse("xesam:album" in metadata)
    }

    @Test
    fun `Metadata carries the album when there is one`() {
        metadataFlow.value = nowPlaying(album = "Some Album")

        assertEquals("Some Album", mpris.getMetadata().getValue("xesam:album").value)
    }

    @Test
    fun `Metadata art URL points at the https image endpoint when the server uses ssl`() {
        val imageId = randomPlatformUUID()
        startKoinWithServer("synara.example", 4433, ssl = true, sslOverride = null)
        metadataFlow.value = nowPlaying(imageId = imageId)

        assertEquals(
            "https://synara.example:4433/image/imageData/$imageId",
            mpris.getMetadata().getValue("mpris:artUrl").value
        )
    }

    @Test
    fun `Metadata art URL falls back to http when the session ssl override is false`() {
        val imageId = randomPlatformUUID()
        startKoinWithServer("synara.example", 4433, ssl = true, sslOverride = false)
        metadataFlow.value = nowPlaying(imageId = imageId)

        assertEquals(
            "http://synara.example:4433/image/imageData/$imageId",
            mpris.getMetadata().getValue("mpris:artUrl").value
        )
    }

    @Test
    fun `Metadata art URL uses http when the server does not use ssl`() {
        val imageId = randomPlatformUUID()
        startKoinWithServer("synara.example", 4433, ssl = false, sslOverride = null)
        metadataFlow.value = nowPlaying(imageId = imageId)

        assertEquals(
            "http://synara.example:4433/image/imageData/$imageId",
            mpris.getMetadata().getValue("mpris:artUrl").value
        )
    }

    @Test
    fun `Metadata art URL points at the cached cover file when no server host is known`() {
        val imageId = randomPlatformUUID()
        startKoinWithServer(null, null, ssl = false, sslOverride = null)
        metadataFlow.value = nowPlaying(imageId = imageId)

        assertEquals("file://$dir/covers/$imageId.jpg", mpris.getMetadata().getValue("mpris:artUrl").value)
    }

    @Test
    fun `Metadata has no art URL when the track has no image`() {
        startKoinWithServer("synara.example", 4433, ssl = true, sslOverride = null)
        metadataFlow.value = nowPlaying(imageId = null)

        assertNull(mpris.getMetadata()["mpris:artUrl"])
    }

    @Test
    fun `Metadata has no art URL when Koin is not started`() {
        metadataFlow.value = nowPlaying(imageId = randomPlatformUUID())

        assertNull(mpris.getMetadata()["mpris:artUrl"])
    }

    @Test
    fun `Next forwards to the bridge`() {
        mpris.Next()

        verify(exactly = 1) { bridge.next() }
    }

    @Test
    fun `Previous forwards to the bridge`() {
        mpris.Previous()

        verify(exactly = 1) { bridge.previous() }
    }

    @Test
    fun `Pause forwards to the bridge`() {
        mpris.Pause()

        verify(exactly = 1) { bridge.pause() }
    }

    @Test
    fun `PlayPause toggles playback on the bridge`() {
        mpris.PlayPause()

        verify(exactly = 1) { bridge.togglePlayPause() }
    }

    @Test
    fun `Stop forwards to the bridge`() {
        mpris.Stop()

        verify(exactly = 1) { bridge.stop() }
    }

    @Test
    fun `Play forwards to the bridge`() {
        mpris.Play()

        verify(exactly = 1) { bridge.play() }
    }

    @Test
    fun `Quit stops playback on the bridge`() {
        mpris.Quit()

        verify(exactly = 1) { bridge.stop() }
    }
}
