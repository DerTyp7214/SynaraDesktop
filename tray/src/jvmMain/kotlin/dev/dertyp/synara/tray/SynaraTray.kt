package dev.dertyp.synara.tray

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.concurrent.thread

class JvmSynaraTray : SynaraTray {
    private var indicator: Pointer? = null
    private var menu: Pointer? = null
    private var baseIcon: BufferedImage? = null
    private var pendingBadgeColor: Color? = null

    private var lastExtractedIconPath: String? = null
    private var lastBadgedIconPath: String? = null

    private var onActionCallback: GCallback? = null
    private var onExitCallback: GCallback? = null

    @Suppress("FunctionName", "LocalVariableName")
    private interface AppIndicatorLib : Library {
        companion object {
            const val CATEGORY_APPLICATION_STATUS = 0
            const val STATUS_ACTIVE = 1

            val INSTANCE: AppIndicatorLib = try {
                Native.load("ayatana-appindicator3-0.1", AppIndicatorLib::class.java)
            } catch (_: Throwable) {
                try {
                    Native.load("ayatana-appindicator3", AppIndicatorLib::class.java)
                } catch (_: Throwable) {
                    Native.load("appindicator3", AppIndicatorLib::class.java)
                }
            }
        }

        fun app_indicator_new(id: String, icon_name: String, category: Int): Pointer
        fun app_indicator_set_status(indicator: Pointer, status: Int)
        fun app_indicator_set_menu(indicator: Pointer, menu: Pointer)
        fun app_indicator_set_icon_full(indicator: Pointer, icon_name: String, icon_desc: String)
    }

    @Suppress("FunctionName", "LocalVariableName")
    private interface GtkLib : Library {
        companion object {
            val INSTANCE: GtkLib = Native.load("gtk-3", GtkLib::class.java)
        }

        fun gtk_init(argc: Pointer?, argv: Pointer?): Boolean
        fun gtk_main()
        fun gtk_main_quit()
        fun gtk_menu_new(): Pointer
        fun gtk_menu_item_new_with_label(label: String): Pointer
        fun gtk_menu_shell_append(menu_shell: Pointer, child: Pointer)
        fun gtk_widget_show_all(widget: Pointer)
        fun g_signal_connect_data(
            instance: Pointer,
            detailed_signal: String,
            handler: Callback,
            data: Pointer?,
            destroy_data: Pointer?,
            connect_flags: Int
        ): Long
    }

    private interface GCallback : Callback {
        fun invoke(widget: Pointer, data: Pointer?)
    }

    override fun show(
        iconPath: String,
        tooltip: String,
        onAction: () -> Unit,
        onExit: () -> Unit
    ) {
        thread(isDaemon = true, name = "SynaraTrayNative") {
            try {
                GtkLib.INSTANCE.gtk_init(null, null)

                val resource = Thread.currentThread().contextClassLoader.getResource(iconPath)
                    ?: this::class.java.classLoader.getResource(iconPath)

                baseIcon = try {
                    if (resource != null && !iconPath.endsWith(".svg")) {
                        ImageIO.read(resource)
                    } else {
                        val pngResource = Thread.currentThread().contextClassLoader.getResource("tray-white.png")
                            ?: this::class.java.classLoader.getResource("tray-white.png")
                        if (pngResource != null) ImageIO.read(pngResource) else null
                    }
                } catch (_: Exception) {
                    null
                }

                val absoluteIconPath = findAbsoluteIconPath(iconPath)
                lastExtractedIconPath = absoluteIconPath
                
                indicator = AppIndicatorLib.INSTANCE.app_indicator_new(
                    "dev.dertyp.synara",
                    absoluteIconPath ?: "application-x-executable",
                    AppIndicatorLib.CATEGORY_APPLICATION_STATUS
                )

                menu = GtkLib.INSTANCE.gtk_menu_new()

                val showItem = GtkLib.INSTANCE.gtk_menu_item_new_with_label("Show/Hide")
                onActionCallback = object : GCallback {
                    override fun invoke(widget: Pointer, data: Pointer?) { onAction() }
                }
                GtkLib.INSTANCE.g_signal_connect_data(showItem, "activate", onActionCallback!!, null, null, 0)
                GtkLib.INSTANCE.gtk_menu_shell_append(menu!!, showItem)

                val quitItem = GtkLib.INSTANCE.gtk_menu_item_new_with_label("Exit")
                onExitCallback = object : GCallback {
                    override fun invoke(widget: Pointer, data: Pointer?) { onExit() }
                }
                GtkLib.INSTANCE.g_signal_connect_data(quitItem, "activate", onExitCallback!!, null, null, 0)
                GtkLib.INSTANCE.gtk_menu_shell_append(menu!!, quitItem)

                GtkLib.INSTANCE.gtk_widget_show_all(menu!!)
                AppIndicatorLib.INSTANCE.app_indicator_set_menu(indicator!!, menu!!)
                AppIndicatorLib.INSTANCE.app_indicator_set_status(indicator!!, AppIndicatorLib.STATUS_ACTIVE)

                if (pendingBadgeColor != null) {
                    setBadge(pendingBadgeColor)
                }

                GtkLib.INSTANCE.gtk_main()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun findAbsoluteIconPath(iconPath: String): String? {
        if (File(iconPath).isAbsolute && File(iconPath).exists()) return iconPath
        try {
            val resource = Thread.currentThread().contextClassLoader.getResource(iconPath)
                ?: this::class.java.classLoader.getResource(iconPath)
            
            if (resource != null) {
                val extension = if (iconPath.endsWith(".svg")) ".svg" else ".png"
                val tempFile = Files.createTempFile("synara-tray", extension)
                tempFile.toFile().deleteOnExit()
                resource.openStream().use { input ->
                    Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                }
                return tempFile.toAbsolutePath().toString()
            }
        } catch (_: Exception) {}
        return null
    }

    override fun setBadge(color: Color?) {
        pendingBadgeColor = color
        val indicator = indicator ?: return
        val icon = baseIcon ?: return

        thread(isDaemon = true, name = "SynaraTrayBadge") {
            try {
                if (color != null) {
                    val bi = BufferedImage(icon.width, icon.height, BufferedImage.TYPE_INT_ARGB)
                    val g2d = bi.createGraphics()
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2d.drawImage(icon, 0, 0, null)

                    val badgeSize = (icon.width * 0.25).toInt()
                    val x = icon.width - badgeSize
                    
                    g2d.color = java.awt.Color(0, 0, 0, 120)
                    g2d.fillOval(x - 1, -1, badgeSize + 2, badgeSize + 2)
                    g2d.color = java.awt.Color(color.toArgb(), true)
                    g2d.fillOval(x, 0, badgeSize, badgeSize)
                    g2d.dispose()

                    val tempFile = Files.createTempFile("synara-tray-badged", ".png").toFile()
                    tempFile.deleteOnExit()
                    
                    ImageIO.write(bi, "png", tempFile)
                    AppIndicatorLib.INSTANCE.app_indicator_set_icon_full(indicator, tempFile.absolutePath, "synara")
                    
                    lastBadgedIconPath?.let { File(it).delete() }
                    lastBadgedIconPath = tempFile.absolutePath
                } else {
                    val originalPath = lastExtractedIconPath ?: findAbsoluteIconPath("tray.svg") ?: findAbsoluteIconPath("tray-white.png")
                    if (originalPath != null) {
                        AppIndicatorLib.INSTANCE.app_indicator_set_icon_full(indicator, originalPath, "synara")
                    }
                    lastBadgedIconPath?.let { File(it).delete() }
                    lastBadgedIconPath = null
                }
            } catch (e: Exception) {
                System.err.println("SynaraTray: Failed to update badge: ${e.message}")
            }
        }
    }

    override fun hide() {
        try { GtkLib.INSTANCE.gtk_main_quit() } catch (_: Exception) {}
    }
}

actual fun createSynaraTray(): SynaraTray = JvmSynaraTray()
