package dev.dertyp.synara.utils

import dev.dertyp.getPlatformName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.net.InetAddress
import java.util.TimeZone

actual fun currentTimezoneId(): String = TimeZone.getDefault().id

private val cachedDeviceName: String by lazy {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
    if (hostName != null) return@lazy hostName

    val userName = runCatching { System.getProperty("user.name") }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    if (userName != null) "$userName (${getPlatformName()})" else "Synara Desktop"
}

actual fun defaultDeviceName(): String = cachedDeviceName

private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

actual suspend fun pickImageBytes(): ByteArray? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, "Select image", FileDialog.LOAD)
    dialog.filenameFilter = FilenameFilter { _, name ->
        name.substringAfterLast('.', "").lowercase() in imageExtensions
    }
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext null
    val fileName = dialog.file ?: return@withContext null
    if (fileName.substringAfterLast('.', "").lowercase() !in imageExtensions) return@withContext null
    val file = File(directory, fileName)
    if (!file.isFile) return@withContext null
    file.readBytes()
}
