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

actual suspend fun pickImageBytes(): ByteArray? = pickFile("Select image", imageExtensions)?.bytes

actual suspend fun pickFile(title: String, extensions: Set<String>): PickedFile? = withContext(Dispatchers.IO) {
    val allowed = extensions.map { it.removePrefix(".").lowercase() }.toSet()
    val accepts: (String) -> Boolean = { name -> allowed.isEmpty() || name.substringAfterLast('.', "").lowercase() in allowed }
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.filenameFilter = FilenameFilter { _, name -> accepts(name) }
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext null
    val fileName = dialog.file ?: return@withContext null
    if (!accepts(fileName)) return@withContext null
    val file = File(directory, fileName)
    if (!file.isFile) return@withContext null
    PickedFile(file.name, file.readBytes())
}
