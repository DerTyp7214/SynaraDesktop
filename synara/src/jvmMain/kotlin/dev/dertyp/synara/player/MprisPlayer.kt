@file:Suppress("LocalVariableName")

package dev.dertyp.synara.player

import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.rpc.RpcServiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.koin.java.KoinJavaComponent.getKoin

@Suppress("FunctionName")
@DBusInterfaceName("org.mpris.MediaPlayer2")
@DBusProperty(name = "CanQuit", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Fullscreen", type = Boolean::class, access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "CanSetFullscreen", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Identity", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(
    name = "SupportedUriSchemes",
    type = Array<String>::class,
    access = DBusProperty.Access.READ
)
@DBusProperty(
    name = "SupportedMimeTypes",
    type = Array<String>::class,
    access = DBusProperty.Access.READ
)
interface IMprisMediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()

    fun getCanQuit(): Boolean
    fun getFullscreen(): Boolean
    fun setFullscreen(b: Boolean)
    fun getCanSetFullscreen(): Boolean
    fun getCanRaise(): Boolean
    fun getHasTrackList(): Boolean
    fun getIdentity(): String
    fun getDesktopEntry(): String
    fun getSupportedUriSchemes(): Array<String>
    fun getSupportedMimeTypes(): Array<String>
}

@Suppress("FunctionName", "LocalVariableName")
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@DBusProperty(name = "PlaybackStatus", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "LoopStatus", type = String::class, access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "Rate", type = Double::class, access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "Shuffle", type = Boolean::class, access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "Metadata", type = Map::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Volume", type = Double::class, access = DBusProperty.Access.READ_WRITE)
@DBusProperty(name = "Position", type = Long::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MinimumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = DBusProperty.Access.READ)
interface IMprisMediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(Offset: Long)
    fun SetPosition(TrackId: DBusPath, Position: Long)
    fun OpenUri(Uri: String)

    fun getPlaybackStatus(): String
    fun getLoopStatus(): String
    fun setLoopStatus(status: String)
    fun getRate(): Double
    fun setRate(rate: Double)
    fun getShuffle(): Boolean
    fun setShuffle(shuffle: Boolean)
    fun getMetadata(): Map<String, Variant<*>>
    fun getVolume(): Double
    fun setVolume(volume: Double)
    fun getPosition(): Long
    fun getMinimumRate(): Double
    fun getMaximumRate(): Double
    fun getCanGoNext(): Boolean
    fun getCanGoPrevious(): Boolean
    fun getCanPlay(): Boolean
    fun getCanPause(): Boolean
    fun getCanSeek(): Boolean
    fun getCanControl(): Boolean
}

open class MprisObjectImpl(private val playerModel: PlayerModel) : IMprisMediaPlayer2,
    IMprisMediaPlayer2Player, Properties {
    override fun isRemote() = false
    override fun getObjectPath() = "/org/mpris/MediaPlayer2"

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interface_name: String?, property_name: String?): A {
        return when (interface_name) {
            "org.mpris.MediaPlayer2" -> when (property_name) {
                "CanQuit" -> getCanQuit() as A
                "Fullscreen" -> getFullscreen() as A
                "CanSetFullscreen" -> getCanSetFullscreen() as A
                "CanRaise" -> getCanRaise() as A
                "HasTrackList" -> getHasTrackList() as A
                "Identity" -> getIdentity() as A
                "DesktopEntry" -> getDesktopEntry() as A
                "SupportedUriSchemes" -> getSupportedUriSchemes() as A
                "SupportedMimeTypes" -> getSupportedMimeTypes() as A
                else -> throw IllegalArgumentException("No such property: $property_name")
            }

            "org.mpris.MediaPlayer2.Player" -> when (property_name) {
                "PlaybackStatus" -> getPlaybackStatus() as A
                "LoopStatus" -> getLoopStatus() as A
                "Rate" -> getRate() as A
                "Shuffle" -> getShuffle() as A
                "Metadata" -> getMetadata() as A
                "Volume" -> getVolume() as A
                "Position" -> getPosition() as A
                "MinimumRate" -> getMinimumRate() as A
                "MaximumRate" -> getMaximumRate() as A
                "CanGoNext" -> getCanGoNext() as A
                "CanGoPrevious" -> getCanGoPrevious() as A
                "CanPlay" -> getCanPlay() as A
                "CanPause" -> getCanPause() as A
                "CanSeek" -> getCanSeek() as A
                "CanControl" -> getCanControl() as A
                else -> throw IllegalArgumentException("No such property: $property_name")
            }

            else -> throw IllegalArgumentException("No such interface: $interface_name")
        }
    }

    override fun <A> Set(interface_name: String?, property_name: String?, value: A) {
        val v = if (value is Variant<*>) value.value else value
        when (interface_name) {
            "org.mpris.MediaPlayer2" -> if (property_name == "Fullscreen") setFullscreen(v as Boolean)
            "org.mpris.MediaPlayer2.Player" -> when (property_name) {
                "LoopStatus" -> setLoopStatus(v as String)
                "Rate" -> setRate(v as Double)
                "Shuffle" -> setShuffle(v as Boolean)
                "Volume" -> setVolume(v as Double)
            }
        }
    }

    override fun GetAll(interface_name: String?): MutableMap<String, Variant<*>> {
        val res = mutableMapOf<String, Variant<*>>()
        when (interface_name) {
            "org.mpris.MediaPlayer2" -> {
                res["CanQuit"] = Variant(getCanQuit())
                res["Fullscreen"] = Variant(getFullscreen())
                res["CanSetFullscreen"] = Variant(getCanSetFullscreen())
                res["CanRaise"] = Variant(getCanRaise())
                res["HasTrackList"] = Variant(getHasTrackList())
                res["Identity"] = Variant(getIdentity())
                res["DesktopEntry"] = Variant(getDesktopEntry())
                res["SupportedUriSchemes"] = Variant(getSupportedUriSchemes())
                res["SupportedMimeTypes"] = Variant(getSupportedMimeTypes())
            }

            "org.mpris.MediaPlayer2.Player" -> {
                res["PlaybackStatus"] = Variant(getPlaybackStatus())
                res["LoopStatus"] = Variant(getLoopStatus())
                res["Rate"] = Variant(getRate())
                res["Shuffle"] = Variant(getShuffle())
                res["Metadata"] = Variant(getMetadata(), "a{sv}")
                res["Volume"] = Variant(getVolume())
                res["Position"] = Variant(getPosition())
                res["MinimumRate"] = Variant(getMinimumRate())
                res["MaximumRate"] = Variant(getMaximumRate())
                res["CanGoNext"] = Variant(getCanGoNext())
                res["CanGoPrevious"] = Variant(getCanGoPrevious())
                res["CanPlay"] = Variant(getCanPlay())
                res["CanPause"] = Variant(getCanPause())
                res["CanSeek"] = Variant(getCanSeek())
                res["CanControl"] = Variant(getCanControl())
            }
        }
        return res
    }

    override fun Raise() {}
    override fun Quit() {
        playerModel.stop()
    }

    override fun Next() = playerModel.skipNext()
    override fun Previous() = playerModel.skipPrevious()
    override fun Pause() = playerModel.pause()
    override fun PlayPause() = playerModel.togglePlayPause()
    override fun Stop() = playerModel.stop()
    override fun Play() = playerModel.play()
    override fun Seek(Offset: Long) =
        playerModel.seekTo(playerModel.currentPosition.value + (Offset / 1000))

    override fun SetPosition(TrackId: DBusPath, Position: Long) =
        playerModel.seekTo(Position / 1000)

    override fun OpenUri(Uri: String) {}

    @DBusBoundProperty(name = "CanQuit")
    override fun getCanQuit() = true
    @DBusBoundProperty(name = "Fullscreen")
    override fun getFullscreen() = false
    override fun setFullscreen(b: Boolean) {}
    @DBusBoundProperty(name = "CanSetFullscreen")
    override fun getCanSetFullscreen() = false
    @DBusBoundProperty(name = "CanRaise")
    override fun getCanRaise() = false
    @DBusBoundProperty(name = "HasTrackList")
    override fun getHasTrackList() = false
    @DBusBoundProperty(name = "Identity")
    override fun getIdentity() = if (BuildConfig.IS_DEBUG) "Synara Dev" else "Synara"
    @DBusBoundProperty(name = "DesktopEntry")
    override fun getDesktopEntry() = if (BuildConfig.IS_DEBUG) "synara-dev" else "synara"
    @DBusBoundProperty(name = "SupportedUriSchemes")
    override fun getSupportedUriSchemes() = emptyArray<String>()
    @DBusBoundProperty(name = "SupportedMimeTypes")
    override fun getSupportedMimeTypes() = emptyArray<String>()

    @DBusBoundProperty(name = "PlaybackStatus")
    override fun getPlaybackStatus(): String =
        if (playerModel.isPlaying.value) "Playing" else "Paused"

    @DBusBoundProperty(name = "LoopStatus")
    override fun getLoopStatus() = when (playerModel.repeatMode.value) {
        RepeatMode.OFF -> "None"
        RepeatMode.ALL -> "Playlist"
        RepeatMode.ONE -> "Track"
    }

    override fun setLoopStatus(status: String) {
        val target = when (status) {
            "None" -> RepeatMode.OFF
            "Playlist" -> RepeatMode.ALL
            "Track" -> RepeatMode.ONE
            else -> return
        }
        playerModel.setRepeatMode(target)
    }

    @DBusBoundProperty(name = "Rate")
    override fun getRate() = 1.0
    override fun setRate(rate: Double) {}
    @DBusBoundProperty(name = "Shuffle")
    override fun getShuffle() = playerModel.shuffleMode.value
    override fun setShuffle(shuffle: Boolean) {
        if (playerModel.shuffleMode.value != shuffle) playerModel.toggleShuffle()
    }

    @DBusBoundProperty(name = "Metadata")
    override fun getMetadata(): Map<String, Variant<*>> =
        createMetadata(playerModel.currentSong.value)

    @DBusBoundProperty(name = "Volume")
    override fun getVolume() = playerModel.volume.value.toDouble()
    override fun setVolume(volume: Double) = playerModel.setVolume(volume.toFloat())
    @DBusBoundProperty(name = "Position")
    override fun getPosition() = playerModel.currentPosition.value * 1000L
    @DBusBoundProperty(name = "MinimumRate")
    override fun getMinimumRate() = 1.0
    @DBusBoundProperty(name = "MaximumRate")
    override fun getMaximumRate() = 1.0
    @DBusBoundProperty(name = "CanGoNext")
    override fun getCanGoNext() = true
    @DBusBoundProperty(name = "CanGoPrevious")
    override fun getCanGoPrevious() = true
    @DBusBoundProperty(name = "CanPlay")
    override fun getCanPlay() = true
    @DBusBoundProperty(name = "CanPause")
    override fun getCanPause() = true
    @DBusBoundProperty(name = "CanSeek")
    override fun getCanSeek() = true
    @DBusBoundProperty(name = "CanControl")
    override fun getCanControl() = true
}

class MprisPlayer(private val playerModel: PlayerModel) : IMprisPlayer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: DBusConnection? = null
    private var isStarted = false

    override fun start() {
        if (isStarted || !System.getProperty("os.name").lowercase().contains("linux")) return
        isStarted = true

        scope.launch {
            try {
                connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                val busName = if (BuildConfig.IS_DEBUG) "org.mpris.MediaPlayer2.synara-dev" else "org.mpris.MediaPlayer2.synara"
                connection?.requestBusName(busName)
                connection?.exportObject("/org/mpris/MediaPlayer2", MprisObjectImpl(playerModel))

                launch {
                    playerModel.isPlaying.collectLatest {
                        sendPropertiesChangedSignal(
                            "PlaybackStatus",
                            Variant(if (it) "Playing" else "Paused")
                        )
                    }
                }
                launch {
                    playerModel.currentSong.collectLatest {
                        sendPropertiesChangedSignal(
                            "Metadata",
                            Variant(createMetadata(it), "a{sv}")
                        )
                    }
                }
                launch {
                    playerModel.volume.collectLatest {
                        sendPropertiesChangedSignal(
                            "Volume",
                            Variant(it.toDouble())
                        )
                    }
                }
                launch {
                    playerModel.repeatMode.collectLatest {
                        val status = when (it) {
                            RepeatMode.OFF -> "None"
                            RepeatMode.ALL -> "Playlist"
                            RepeatMode.ONE -> "Track"
                        }
                        sendPropertiesChangedSignal("LoopStatus", Variant(status))
                    }
                }
                launch {
                    playerModel.shuffleMode.collectLatest {
                        sendPropertiesChangedSignal(
                            "Shuffle",
                            Variant(it)
                        )
                    }
                }

                while (isActive && connection?.isConnected == true) delay(2000)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isStarted = false
                try {
                    connection?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendPropertiesChangedSignal(property: String, value: Variant<*>) {
        try {
            connection?.sendMessage(
                Properties.PropertiesChanged(
                    "/org/mpris/MediaPlayer2",
                    "org.mpris.MediaPlayer2.Player",
                    mapOf(property to value),
                    emptyList()
                )
            )
        } catch (_: Exception) {
        }
    }
}

private fun createMetadata(song: UserSong?): Map<String, Variant<*>> {
    val m = mutableMapOf<String, Variant<*>>()
    val idStr = song?.id?.toString()?.replace("-", "_") ?: "no_track"
    m["mpris:trackid"] = Variant(DBusPath("/org/mpris/MediaPlayer2/track/$idStr"))
    if (song != null) {
        m["mpris:length"] = Variant(song.duration * 1000L)
        m["xesam:title"] = Variant(song.title)
        m["xesam:artist"] = Variant(song.artists.map { it.name }.toTypedArray(), "as")
        song.album?.let { m["xesam:album"] = Variant(it.name) }

        try {
            val manager = getKoin().get<RpcServiceManager>()
            val host = manager.host
            val port = manager.port
            if (host != null && port != null && song.coverId != null) {
                m["mpris:artUrl"] = Variant("http://$host:$port/image/byId/${song.coverId}")
            } else {
                song.coverId?.let {
                    m["mpris:artUrl"] =
                        Variant("file://${System.getProperty("user.home")}/.synara/cache/covers/$it.jpg")
                }
            }
        } catch (_: Exception) {
            song.coverId?.let {
                m["mpris:artUrl"] =
                    Variant("file://${System.getProperty("user.home")}/.synara/cache/covers/$it.jpg")
            }
        }
    }
    return m
}
