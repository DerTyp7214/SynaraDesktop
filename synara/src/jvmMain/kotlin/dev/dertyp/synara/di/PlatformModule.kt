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

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
    singleOf(::MprisPlayer) bind IMprisPlayer::class
    single<SqlDriver> {
        val databasePath = File(System.getProperty("user.home"), ".synara/synara.db")
        databasePath.parentFile.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")

        if (!databasePath.exists() || databasePath.length() == 0L) {
            SynaraDatabase.Schema.create(driver)
        }
        
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
