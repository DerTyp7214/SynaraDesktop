package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.SongGuessSavedGames
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V1_5__SongGuessSavedGame : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val createStatements = transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            SchemaUtils.createStatements(SongGuessSavedGames)
        }.filter { it.contains(SongGuessSavedGames.tableName, ignoreCase = true) }

        context.connection.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS ${SongGuessSavedGames.tableName}")
            for (sql in createStatements) {
                statement.execute(sql)
            }
        }
    }
}
