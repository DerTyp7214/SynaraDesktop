package dev.dertyp.synara.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.IMprisPlayer
import dev.dertyp.synara.player.JvmAudioPlayer
import dev.dertyp.synara.player.MprisPlayer
import dev.dertyp.synara.utils.getAppDataDir
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

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
    
    val koin = getKoin()
    val logger = koin.get<Logger>()
    logger.info(LogTag("platform"), "Application directory: ${getAppDataDir().absolutePath}")

    try {
        val mprisPlayer = koin.get<IMprisPlayer>()
        mprisPlayer.start()
    } catch (_: Exception) {
    }
}
