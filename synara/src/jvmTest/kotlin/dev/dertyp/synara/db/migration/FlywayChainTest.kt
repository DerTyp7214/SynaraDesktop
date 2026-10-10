package dev.dertyp.synara.db.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dertyp.synara.db.*
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywayChainTest {
    private lateinit var file: Path
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database

    private val migrationClasses = listOf(
        V1__InitLibrary::class,
        V1_1__InitOfflineLibrary::class,
        V1_2__DecoupleScrobblingFromLibrary::class,
        V1_3__MusicBrainzUUID::class,
        V1_4__SongGuessLeaderboard::class,
        V1_5__SongGuessSavedGame::class,
        V1_6__SongAudioInfo::class,
        V1_7__SongTitleTags::class,
        V1_8__SettingsSyncKnown::class,
        V1_9__SongSuperLikes::class,
        V1_10__SharedModelRenames::class,
        V1_11__AlbumTitleTags::class,
        V1_12__RepairForeignKeyTargets::class,
        V1_13__UserServer::class
    )

    private val allTables: Array<Table> = arrayOf(
        DownloadedImages,
        DownloadedUsers,
        DownloadedSongs,
        DownloadedAlbums,
        DownloadedArtists,
        DownloadedGenres,
        DownloadedPlaylists,
        DownloadedUserPlaylists,
        DownloadedSongArtists,
        DownloadedAlbumArtists,
        DownloadedArtistMembers,
        DownloadedPlaylistSongs,
        DownloadedUserPlaylistSongs,
        DownloadedSongGenres,
        DownloadedAlbumGenres,
        DownloadedArtistGenres,
        RecentlyPlayedSongs,
        RecentlyPlayedAlbums,
        RecentlyPlayedArtists,
        ScrobbleQueue,
        LocalHistory,
        SongGuessGames,
        SongGuessSavedGames,
        SettingsSyncKnownEntries
    )

    @BeforeTest
    fun setUp() {
        file = Files.createTempFile("synara-flyway-chain", ".db")
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:$file"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "5000")
            }
        )
        startTestKoin(module { single { dataSource } })
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:dev/dertyp/synara/db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
        database = Database.connect(dataSource)
        TransactionManager.defaultDatabase = database
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) TransactionManager.closeAndUnregister(database)
        if (::dataSource.isInitialized) dataSource.close()
        stopTestKoin()
        if (!::file.isInitialized) return
        file.deleteIfExists()
        file.resolveSibling("${file.fileName}-wal").deleteIfExists()
        file.resolveSibling("${file.fileName}-shm").deleteIfExists()
    }

    private fun versionOf(className: String): String = className.removePrefix("V").substringBefore("__").replace('_', '.')

    private fun existingTableNames(): Set<String> = dataSource.connection.use { connection ->
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("TABLE_NAME")) }
        }
    }

    @Test
    fun migratedSchemaNeedsNoFurtherStatements() {
        val missing = transaction(database) { SchemaUtils.statementsRequiredToActualizeScheme(*allTables) }

        assertEquals(emptyList(), missing)
    }

    @Test
    fun migratedDatabaseHoldsExactlyTheDeclaredTables() {
        val migrationBookkeeping = setOf("flyway_schema_history")
        val actual = existingTableNames().filterNot { it in migrationBookkeeping || it.startsWith("sqlite_") }.toSet()

        assertEquals(allTables.map { it.tableName }.toSet(), actual)
    }

    @Test
    fun noMigrationIsLeftPending() {
        val info = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:dev/dertyp/synara/db/migration")
            .baselineOnMigrate(true)
            .load()
            .info()
        val expected = migrationClasses.map { versionOf(it.simpleName!!) }.toSet()
        val applied = info.applied().map { it.version.version }.toSet()

        assertTrue(info.pending().isEmpty())
        assertEquals(expected, applied)
        assertEquals(expected.maxOf { MigrationVersion.fromVersion(it) }, info.current().version)
    }

    @Test
    fun everyForeignKeyPointsAtATableThatExists() {
        val tables = existingTableNames()

        val dangling = dataSource.connection.use { connection ->
            tables.flatMap { table ->
                connection.metaData.getImportedKeys(null, null, table).use { rs ->
                    buildList { while (rs.next()) add(table to rs.getString("PKTABLE_NAME")) }
                }
            }
        }.filter { (_, target) -> target !in tables }

        assertEquals(emptyList(), dangling)
    }
}
