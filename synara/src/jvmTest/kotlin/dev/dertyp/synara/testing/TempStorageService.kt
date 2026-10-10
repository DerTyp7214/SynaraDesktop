package dev.dertyp.synara.testing

import dev.dertyp.data.BaseSong
import dev.dertyp.synara.services.LocalStorageService
import java.nio.file.Path

class TempStorageService(private val dir: Path) : LocalStorageService {
    override fun getConfigDir(): String = dir.toString()
    override fun getCacheDir(): String = dir.toString()
    override fun getDataDir(): String = dir.toString()
    override fun getSystemMusicDir(): String = dir.toString()
    override fun getInternalSongDir(): String = dir.toString()
    override fun getInternalSongPath(song: BaseSong): String = dir.toString()
    override fun linkSongToSystemMusicDir(song: BaseSong) = Unit
    override fun unlinkSongFromSystemMusicDir(song: BaseSong) = Unit
}
