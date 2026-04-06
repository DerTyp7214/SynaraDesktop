package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V1_2__DecoupleScrobblingFromLibrary : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val tables = arrayOf(
            RecentlyPlayedSongs,
            RecentlyPlayedAlbums,
            RecentlyPlayedArtists,
            ScrobbleQueue,
            LocalHistory
        )
        val tableNames = tables.map { it.tableName }

        val createStatements = transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            SchemaUtils.createStatements(*tables)
        }

        val filteredStatements = createStatements.filter { sql ->
            tableNames.any { name -> sql.contains(name, ignoreCase = true) }
        }

        context.connection.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS recentlyPlayedSong")
            statement.execute("DROP TABLE IF EXISTS recentlyPlayedAlbum")
            statement.execute("DROP TABLE IF EXISTS recentlyPlayedArtist")
            statement.execute("DROP TABLE IF EXISTS scrobbleQueue")
            statement.execute("DROP TABLE IF EXISTS localHistory")

            for (sql in filteredStatements) {
                statement.execute(sql)
            }
        }
    }
}
