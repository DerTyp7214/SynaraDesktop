package dev.dertyp.synara.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.util.TimeZone

actual fun currentTimezoneId(): String = TimeZone.getDefault().id

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
