package dev.dertyp.synara.utils

import dev.dertyp.synara.BuildConfig
import java.io.File

fun getAppDataDir(): File {
    if (BuildConfig.IS_DEBUG) {
        var current = File(".").absoluteFile
        var rootDir = current
        
        while (current != null) {
            if (File(current, "settings.gradle.kts").exists() || File(current, "gradlew").exists()) {
                rootDir = current
                break
            }
            current = current.parentFile
        }

        val devDir = File(rootDir, "dev")
        if (!devDir.exists()) {
            devDir.mkdirs()
        }
        return devDir
    }

    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    
    val dir = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (appData != null) File(appData, "synara") else File(home, "AppData/Roaming/synara")
        }
        os.contains("mac") -> {
            File(home, "Library/Application Support/synara")
        }
        else -> {
            val xdgConfig = System.getenv("XDG_CONFIG_HOME")
            if (xdgConfig != null && xdgConfig.isNotEmpty()) {
                File(xdgConfig, "synara")
            } else {
                File(home, ".config/synara")
            }
        }
    }
    
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}
