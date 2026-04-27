package dev.dertyp.synara.services

import dev.dertyp.data.BaseSong
import dev.dertyp.synara.BuildConfig
import dev.dertyp.synara.utils.OSUtils
import java.io.File
import java.nio.file.Files

class JvmLocalStorageService : LocalStorageService {
    override fun getConfigDir(): String = getAppDir(AppDirType.Config).absolutePath
    override fun getCacheDir(): String = getAppDir(AppDirType.Cache).absolutePath
    override fun getDataDir(): String = getAppDir(AppDirType.Data).absolutePath

    override fun getSystemMusicDir(): String {
        val home = System.getProperty("user.home")
        val musicDir = File(home, "Music")
        return File(musicDir, "Synara").also { if (!it.exists()) it.mkdirs() }.absolutePath
    }

    override fun getInternalSongDir(): String {
        return File(getDataDir(), "songs").also { if (!it.exists()) it.mkdirs() }.absolutePath
    }

    override fun getInternalSongPath(song: BaseSong): String {
        val relativePath = song.path.removePrefix("/data/Tidal/Tracks").trimStart('/', '\\')
        val file = File(getInternalSongDir(), relativePath)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        return file.absolutePath
    }

    override fun linkSongToSystemMusicDir(song: BaseSong) {
        val internalPath = getInternalSongPath(song)
        val extension = internalPath.substringAfterLast('.', "")
        val albumName = song.album?.name ?: "Unknown Album"
        val songName = song.title
        val songId = song.id.toString()

        val sanitizedAlbum = sanitizeFileName(albumName)
        val sanitizedSong = sanitizeFileName(songName)

        val albumDir = File(getSystemMusicDir(), sanitizedAlbum)
        if (!albumDir.exists()) albumDir.mkdirs()

        val fileName = if (extension.isNotEmpty()) {
            "$sanitizedSong - $songId.$extension"
        } else {
            "$sanitizedSong - $songId"
        }

        val targetFile = File(albumDir, fileName)
        val sourceFile = File(internalPath)

        if (!sourceFile.exists()) return

        try {
            if (targetFile.exists()) targetFile.delete()
            Files.createLink(targetFile.toPath(), sourceFile.toPath())
        } catch (_: Exception) {
            try {
                if (targetFile.exists()) targetFile.delete()
                Files.createSymbolicLink(targetFile.toPath(), sourceFile.toPath())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun unlinkSongFromSystemMusicDir(song: BaseSong) {
        val albumName = song.album?.name ?: "Unknown Album"
        val songName = song.title
        val songId = song.id.toString()

        val sanitizedAlbum = sanitizeFileName(albumName)
        val sanitizedSong = sanitizeFileName(songName)

        val albumDir = File(getSystemMusicDir(), sanitizedAlbum)
        if (!albumDir.exists()) return

        val fileNameSearch = "$sanitizedSong - $songId"
        albumDir.listFiles()?.find { it.name.startsWith(fileNameSearch) }?.delete()
        
        if (albumDir.list()?.isEmpty() == true) {
            albumDir.delete()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun getAppDir(type: AppDirType): File {
        if (BuildConfig.IS_DEBUG) {
            val root = getProjectRoot()
            val devDir = File(root, "dev")
            return File(devDir, type.name.lowercase()).also { if (!it.exists()) it.mkdirs() }
        }

        val home = System.getProperty("user.home")
        val dir = when {
            OSUtils.isWindows -> {
                when (type) {
                    AppDirType.Cache -> {
                        val localAppData = System.getenv("LOCALAPPDATA")
                        if (localAppData != null) File(localAppData, "synara") else File(home, "AppData/Local/synara")
                    }
                    else -> {
                        val appData = System.getenv("APPDATA")
                        if (appData != null) File(appData, "synara") else File(home, "AppData/Roaming/synara")
                    }
                }
            }
            OSUtils.isMac -> {
                when (type) {
                    AppDirType.Cache -> File(home, "Library/Caches/synara")
                    else -> File(home, "Library/Application Support/synara")
                }
            }
            else -> {
                when (type) {
                    AppDirType.Config -> {
                        val xdgConfig = System.getenv("XDG_CONFIG_HOME")
                        if (xdgConfig != null && xdgConfig.isNotEmpty()) File(xdgConfig, "synara") else File(home, ".config/synara")
                    }
                    AppDirType.Cache -> {
                        val xdgCache = System.getenv("XDG_CACHE_HOME")
                        if (xdgCache != null && xdgCache.isNotEmpty()) File(xdgCache, "synara") else File(home, ".cache/synara")
                    }
                    AppDirType.Data -> {
                        val xdgData = System.getenv("XDG_DATA_HOME")
                        if (xdgData != null && xdgData.isNotEmpty()) File(xdgData, "synara") else File(home, ".local/share/synara")
                    }
                }
            }
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getProjectRoot(): File {
        var current = File(".").absoluteFile
        var root = current
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "gradlew").exists()) {
                root = current
                break
            }
            current = current.parentFile
        }
        return root
    }
}
