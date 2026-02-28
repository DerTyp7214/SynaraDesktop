package dev.dertyp.synara.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.dertyp.synara.db.SynaraDatabase
import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.JvmAudioPlayer
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
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
}
