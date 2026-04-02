package dev.dertyp.synara.services

import dev.dertyp.data.BaseSong

enum class AppDirType {
    Config,
    Cache,
    Data
}

interface LocalStorageService {
    fun getConfigDir(): String
    fun getCacheDir(): String
    fun getDataDir(): String
    fun getSystemMusicDir(): String
    fun getInternalSongDir(): String
    fun getInternalSongPath(song: BaseSong): String
    fun linkSongToSystemMusicDir(song: BaseSong)
}
