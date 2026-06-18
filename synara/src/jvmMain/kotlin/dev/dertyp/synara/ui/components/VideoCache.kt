package dev.dertyp.synara.ui.components

import dev.dertyp.synara.services.LocalStorageService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.security.MessageDigest

object VideoCache : KoinComponent {
    private val storageService: LocalStorageService by inject()
    private val cacheDir by lazy {
        File(storageService.getCacheDir(), "videos").also { if (!it.exists()) it.mkdirs() }
    }

    private const val MAX_ENTRIES = 100

    fun getFileFromBytes(key: String, bytes: ByteArray?): File? {
        if (bytes == null) return null
        val hash = sha256(key)
        val file = File(cacheDir, "$hash.mp4")

        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }

        file.writeBytes(bytes)
        cleanup()
        return file
    }

    private fun cleanup() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return

        files.sortBy { it.lastModified() }
        val toDelete = files.size - MAX_ENTRIES
        for (i in 0 until toDelete) {
            files[i].delete()
        }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}
