package dev.dertyp.synara.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.db.DatabaseMigrations
import dev.dertyp.synara.player.*
import dev.dertyp.synara.utils.OSUtils
import dev.dertyp.synara.utils.getAppDataDir
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
    single<SystemMediaManager> {
        when {
            OSUtils.isWindows -> WindowsMediaManager(get())
            OSUtils.isMac -> MacMediaManager(get())
            else -> LinuxMediaManager(get())
        }
    }
    single<SqlDriver> {
        val databasePath = File(getAppDataDir(), "synara.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")

        DatabaseMigrations.migrate(driver)
        
        driver
    }
}

actual fun platformInit() {
    System.setProperty("compose.accessibility.enable", "false")
    
    val koin = getKoin()
    val logger = koin.get<Logger>()
    logger.info(LogTag("platform"), "Application directory: ${getAppDataDir().absolutePath}")

    try {
        val mediaManager = koin.get<SystemMediaManager>()
        mediaManager.start()
    } catch (_: Exception) {
    }
}
