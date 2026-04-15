package dev.dertyp.synara.di

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import dev.dertyp.synara.db.DatabaseMigrationRepository
import dev.dertyp.synara.db.ExposedDatabaseMigrationRepository
import dev.dertyp.synara.db.ExposedLibraryRepository
import dev.dertyp.synara.db.ExposedLocalHistoryRepository
import dev.dertyp.synara.db.ExposedRecentlyPlayedRepository
import dev.dertyp.synara.db.ExposedScrobbleQueueRepository
import dev.dertyp.synara.db.ExposedUserRepository
import dev.dertyp.synara.db.LibraryRepository
import dev.dertyp.synara.db.LocalHistoryRepository
import dev.dertyp.synara.db.RecentlyPlayedRepository
import dev.dertyp.synara.db.ScrobbleQueueRepository
import dev.dertyp.synara.db.UserRepository
import dev.dertyp.synara.player.AudioPlayer
import dev.dertyp.synara.player.JvmAudioPlayer
import dev.dertyp.synara.player.LinuxMediaManager
import dev.dertyp.synara.player.MacMediaManager
import dev.dertyp.synara.player.SystemMediaManager
import dev.dertyp.synara.player.WindowsMediaManager
import dev.dertyp.synara.services.DownloadManager
import dev.dertyp.synara.services.IDownloadManager
import dev.dertyp.synara.services.JvmLocalStorageService
import dev.dertyp.synara.services.JvmVideoFrameService
import dev.dertyp.synara.services.LocalStorageService
import dev.dertyp.synara.services.VideoFrameService
import dev.dertyp.synara.utils.OSUtils
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

actual fun platformModule(): Module = module {
    singleOf(::JvmAudioPlayer) bind AudioPlayer::class
    singleOf(::JvmLocalStorageService) bind LocalStorageService::class
    singleOf(::JvmVideoFrameService) bind VideoFrameService::class
    singleOf(::DownloadManager) bind IDownloadManager::class
    single<SystemMediaManager> {
        when {
            OSUtils.isWindows -> WindowsMediaManager(get())
            OSUtils.isMac -> MacMediaManager(get())
            else -> LinuxMediaManager(get())
        }
    }
    
    single<HikariDataSource> {
        val storageService = get<LocalStorageService>()
        val databasePath = File(storageService.getDataDir(), "synara.db")
        val jdbcUrl = "jdbc:sqlite:${databasePath.absolutePath}"

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 4
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("busy_timeout", "5000")
        }
        HikariDataSource(config)
    }

    single<Database> {
        val dataSource = get<HikariDataSource>()
        
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:dev/dertyp/synara/db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()

        Database.connect(dataSource)
    }

    singleOf(::ExposedRecentlyPlayedRepository) bind RecentlyPlayedRepository::class
    singleOf(::ExposedUserRepository) bind UserRepository::class
    singleOf(::ExposedScrobbleQueueRepository) bind ScrobbleQueueRepository::class
    singleOf(::ExposedLocalHistoryRepository) bind LocalHistoryRepository::class
    singleOf(::ExposedLibraryRepository) bind LibraryRepository::class
    singleOf(::ExposedDatabaseMigrationRepository) bind DatabaseMigrationRepository::class
}

actual fun platformInit() {
    System.setProperty("compose.accessibility.enable", "false")
    
    val koin = getKoin()
    val logger = koin.get<Logger>()
    val storageService = koin.get<LocalStorageService>()
    logger.info(LogTag("platform"), "Application directory: ${storageService.getDataDir()}")

    koin.get<Database>()

    try {
        val mediaManager = koin.get<SystemMediaManager>()
        mediaManager.start()
    } catch (_: Exception) {
    }
}
