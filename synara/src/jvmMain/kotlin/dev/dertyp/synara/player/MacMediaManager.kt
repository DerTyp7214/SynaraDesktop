package dev.dertyp.synara.player

import com.sun.jna.*
import dev.dertyp.core.joinArtists
import dev.dertyp.data.UserSong
import dev.dertyp.services.IImageService
import dev.dertyp.synara.utils.OSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.math.abs
import com.sun.jna.Function as JnaFunction

class MacMediaManager(private val playerModel: PlayerModel) : SystemMediaManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isStarted = false
    private var artworkJob: Job? = null
    private var lastCoverId: String? = null
    private var lastArtwork: Pointer? = null

    @Suppress("FunctionName", "unused")
    private interface ObjCRuntime : Library {
        companion object {
            val INSTANCE: ObjCRuntime = Native.load("objc", ObjCRuntime::class.java)
        }

        fun objc_getClass(name: String): Pointer
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer, vararg args: Any?): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Int): Pointer
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
    }

    private val objcMsgSend: JnaFunction by lazy {
        NativeLibrary.getInstance("objc").getFunction("objc_msgSend")
    }

    private fun msg(receiver: Pointer, selector: String, vararg args: Any?): Pointer? {
        val sel = ObjCRuntime.INSTANCE.sel_registerName(selector)
        return objcMsgSend.invoke(Pointer::class.java, arrayOf(receiver, sel, *args)) as? Pointer
    }

    private fun msgDouble(receiver: Pointer, selector: String, vararg args: Any?): Double {
        val sel = ObjCRuntime.INSTANCE.sel_registerName(selector)
        return objcMsgSend.invokeDouble(arrayOf(receiver, sel, *args))
    }

    private fun nsString(str: String): Pointer {
        val nsStringClass = ObjCRuntime.INSTANCE.objc_getClass("NSString")
        return msg(nsStringClass, "stringWithUTF8String:", str)!!
    }

    private fun nsNumber(value: Double): Pointer {
        val nsNumberClass = ObjCRuntime.INSTANCE.objc_getClass("NSNumber")
        return msg(nsNumberClass, "numberWithDouble:", value)!!
    }

    private fun nsDateNow(): Pointer {
        val nsDateClass = ObjCRuntime.INSTANCE.objc_getClass("NSDate")
        return msg(nsDateClass, "date")!!
    }

    private val playCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            playerModel.play()
            return 0L
        }
    }

    private val pauseCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            playerModel.pause()
            return 0L
        }
    }

    private val nextCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            playerModel.skipNext()
            return 0L
        }
    }

    private val previousCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            playerModel.skipPrevious()
            return 0L
        }
    }

    private val toggleCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            playerModel.togglePlayPause()
            return 0L
        }
    }

    private val seekCallback = object : Callback {
        @Suppress("unused")
        fun invoke(self: Pointer, cmd: Pointer, event: Pointer): Long {
            val seekTime = msgDouble(event, "positionTime")
            playerModel.seekTo((seekTime * 1000).toLong())
            return 0L
        }
    }

    private var targetInstance: Pointer? = null

    override fun start() {
        if (isStarted || !OSUtils.isMac) return
        isStarted = true

        println("[INFO] [platform] Starting MacMediaManager...")
        try {
            NativeLibrary.getInstance("Foundation")
            NativeLibrary.getInstance("MediaPlayer")

            // Create target class for callbacks
            val objc = ObjCRuntime.INSTANCE
            val nsObject = objc.objc_getClass("NSObject")
            val customClass = objc.objc_allocateClassPair(nsObject, "SynaraMediaTarget", 0)

            objc.class_addMethod(customClass, objc.sel_registerName("play:"), playCallback, "q@:@")
            objc.class_addMethod(customClass, objc.sel_registerName("pause:"), pauseCallback, "q@:@")
            objc.class_addMethod(customClass, objc.sel_registerName("next:"), nextCallback, "q@:@")
            objc.class_addMethod(customClass, objc.sel_registerName("previous:"), previousCallback, "q@:@")
            objc.class_addMethod(customClass, objc.sel_registerName("toggle:"), toggleCallback, "q@:@")
            objc.class_addMethod(customClass, objc.sel_registerName("seek:"), seekCallback, "q@:@")

            objc.objc_registerClassPair(customClass)
            targetInstance = msg(msg(customClass, "alloc")!!, "init")

            val commandCenterClass = objc.objc_getClass("MPRemoteCommandCenter")
            val center = msg(commandCenterClass, "sharedCommandCenter")!!

            setupCommand(msg(center, "playCommand")!!, "play:", targetInstance!!)
            setupCommand(msg(center, "pauseCommand")!!, "pause:", targetInstance!!)
            setupCommand(msg(center, "nextTrackCommand")!!, "next:", targetInstance!!)
            setupCommand(msg(center, "previousTrackCommand")!!, "previous:", targetInstance!!)
            setupCommand(msg(center, "togglePlayPauseCommand")!!, "toggle:", targetInstance!!)
            setupCommand(msg(center, "changePlaybackPositionCommand")!!, "seek:", targetInstance!!)

            scope.launch {
                playerModel.currentSong.collectLatest { song ->
                    updateMetadata(song, playerModel.isPlaying.value, playerModel.currentPosition.value)
                }
            }

            scope.launch {
                playerModel.isPlaying.collectLatest { isPlaying ->
                    updateMetadata(playerModel.currentSong.value, isPlaying, playerModel.currentPosition.value)
                }
            }

            scope.launch {
                var lastPos = 0L
                playerModel.currentPosition.collect { currentPos ->
                    if (abs(currentPos - lastPos) > 1000) {
                        updateMetadata(playerModel.currentSong.value, playerModel.isPlaying.value, currentPos)
                    }
                    lastPos = currentPos
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupCommand(command: Pointer, selector: String, target: Pointer) {
        val selEnabled = ObjCRuntime.INSTANCE.sel_registerName("setEnabled:")
        objcMsgSend.invoke(arrayOf(command, selEnabled, 1.toByte()))
        
        val selAddTarget = ObjCRuntime.INSTANCE.sel_registerName("addTarget:action:")
        objcMsgSend.invoke(arrayOf(command, selAddTarget, target, ObjCRuntime.INSTANCE.sel_registerName(selector)))
    }

    private fun updateMetadata(song: UserSong?, isPlaying: Boolean, positionMs: Long) {
        val objc = ObjCRuntime.INSTANCE
        val infoCenterClass = objc.objc_getClass("MPNowPlayingInfoCenter")
        val defaultCenter = msg(infoCenterClass, "defaultCenter") ?: return

        if (song == null) {
            msg(defaultCenter, "setNowPlayingInfo:", Pointer.NULL)
            return
        }

        if (song.coverId?.toString() != lastCoverId) {
            lastCoverId = song.coverId?.toString()
            lastArtwork = null
            artworkJob?.cancel()
            artworkJob = scope.launch {
                val artwork = fetchArtwork(song)
                if (artwork != null) {
                    lastArtwork = artwork
                    updateMetadata(song, isPlaying, positionMs)
                }
            }
        }

        val dictClass = objc.objc_getClass("NSMutableDictionary")
        val dict = msg(dictClass, "dictionary") ?: return

        msg(dict, "setObject:forKey:", nsString(song.title), nsString("title"))
        msg(dict, "setObject:forKey:", nsString(song.artists.joinArtists()), nsString("artist"))
        song.album?.let {
            msg(dict, "setObject:forKey:", nsString(it.name), nsString("albumTitle"))
        }
        msg(dict, "setObject:forKey:", nsNumber(song.duration / 1000.0), nsString("playbackDuration"))
        msg(dict, "setObject:forKey:", nsNumber(positionMs / 1000.0), nsString("elapsedPlaybackTime"))
        msg(dict, "setObject:forKey:", nsNumber(if (isPlaying) 1.0 else 0.0), nsString("playbackRate"))
        msg(dict, "setObject:forKey:", nsNumber(1.0), nsString("defaultPlaybackRate"))
        msg(dict, "setObject:forKey:", nsDateNow(), nsString("nowPlayingInfoPropertyTimestamp"))

        lastArtwork?.let {
            msg(dict, "setObject:forKey:", it, nsString("artwork"))
        }

        msg(defaultCenter, "setNowPlayingInfo:", dict)

        val state = if (isPlaying) 1L else 2L
        val selSetPlaybackState = objc.sel_registerName("setPlaybackState:")
        objcMsgSend.invoke(arrayOf(defaultCenter, selSetPlaybackState, state))
    }

    private suspend fun fetchArtwork(song: UserSong): Pointer? {
        val imageService = getKoin().get<IImageService>()
        if (song.coverId == null) return null

        return try {
            val bytes = imageService.getImageData(song.coverId!!, 256) ?: return null
            createMpArtwork(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun createMpArtwork(bytes: ByteArray): Pointer? {
        val objc = ObjCRuntime.INSTANCE
        val nsDataClass = objc.objc_getClass("NSData")
        val nsData = msg(nsDataClass, "dataWithBytes:length:", bytes, bytes.size) ?: return null

        val nsImageClass = objc.objc_getClass("NSImage")
        val nsImage = msg(msg(nsImageClass, "alloc")!!, "initWithData:", nsData) ?: return null

        val artworkClass = objc.objc_getClass("MPMediaItemArtwork")
        val artwork = try {
            msg(msg(artworkClass, "alloc")!!, "initWithImage:", nsImage)
        } catch (_: Exception) {
            null
        }
        return artwork ?: nsImage
    }
}
