package dev.dertyp.synara.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.IMprisPlayer
import dev.dertyp.synara.player.JvmAudioPlayer
import dev.dertyp.synara.player.MprisPlayer
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

private fun getAppDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    
    val dir = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (appData != null) File(appData, "Synara") else File(home, "AppData/Roaming/Synara")
        }
        os.contains("mac") -> {
            File(home, "Library/Application Support/Synara")
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

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
    singleOf(::MprisPlayer) bind IMprisPlayer::class
    single<SqlDriver> {
        val databasePath = File(getAppDataDir(), "synara.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")

        SynaraDatabase.Schema.create(driver)
        
        driver
    }
}

actual fun platformInit() {
    System.setProperty("compose.accessibility.enable", "false")
    
    try {
        val mprisPlayer = getKoin().get<IMprisPlayer>()
        mprisPlayer.start()
    } catch (_: Exception) {
    }
}
