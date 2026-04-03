package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

class V1__InitLibrary : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val statements = tempConnection {
            SchemaUtils.createStatements(
                RecentlyPlayedSongs,
                RecentlyPlayedAlbums,
                RecentlyPlayedArtists,
                ScrobbleQueue,
                LocalHistory
            )
        }
        context.connection.createStatement().use { statement ->
            for (sql in statements) {
                statement.execute(sql)
            }
        }
    }
}
