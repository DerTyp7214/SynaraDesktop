package dev.dertyp.synara.tray

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class MacSynaraTray : SynaraTray {
    private var trayIcon: TrayIcon? = null
    private var baseIcon: BufferedImage? = null

    override fun show(
        iconPath: String,
        tooltip: String,
        onAction: () -> Unit,
        onExit: () -> Unit
    ) {
        if (!SystemTray.isSupported()) return

        val resource = Thread.currentThread().contextClassLoader.getResource(iconPath)
            ?: this::class.java.classLoader.getResource(iconPath)

        baseIcon = try {
            if (resource != null) {
                ImageIO.read(resource)
            } else {
                val pngResource = Thread.currentThread().contextClassLoader.getResource("tray-black.png")
                    ?: this::class.java.classLoader.getResource("tray-black.png")
                if (pngResource != null) ImageIO.read(pngResource) else null
            }
        } catch (_: Exception) {
            null
        }

        if (baseIcon == null) return

        val tray = SystemTray.getSystemTray()
        val popup = PopupMenu()

        val showItem = MenuItem("Show/Hide")
        showItem.addActionListener { onAction() }
        popup.add(showItem)

        val exitItem = MenuItem("Exit")
        exitItem.addActionListener { onExit() }
        popup.add(exitItem)

        trayIcon = TrayIcon(baseIcon, tooltip, popup)
        trayIcon?.isImageAutoSize = true
        trayIcon?.addActionListener { onAction() }

        try {
            tray.add(trayIcon)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setBadge(color: Color?) {
        val icon = trayIcon ?: return
        val original = baseIcon ?: return

        if (color == null) {
            icon.image = original
            return
        }

        val bi = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_ARGB)
        val g2d = bi.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.drawImage(original, 0, 0, null)

        val badgeSize = (original.width * 0.25).toInt()
        val x = original.width - badgeSize

        g2d.color = java.awt.Color(0, 0, 0, 120)
        g2d.fillOval(x - 1, -1, badgeSize + 2, badgeSize + 2)
        g2d.color = java.awt.Color(color.toArgb(), true)
        g2d.fillOval(x, 0, badgeSize, badgeSize)
        g2d.dispose()

        icon.image = bi
    }

    override fun hide() {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        trayIcon?.let { tray.remove(it) }
    }
}
