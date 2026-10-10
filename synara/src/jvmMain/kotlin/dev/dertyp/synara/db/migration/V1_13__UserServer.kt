package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.DownloadedUsers
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

private const val CREATED_BY_STATEMENT = "c"

class V1_13__UserServer : BaseJavaMigration() {
    private class ExistingIndex(val name: String, val unique: Boolean, val origin: String?, val columns: List<String?>)

    override fun migrate(context: Context) {
        val conn = context.connection
        val table = DownloadedUsers.tableName

        val columns = mutableSetOf<String>()
        conn.metaData.getColumns(null, null, table, null).use { rs ->
            while (rs.next()) columns.add(rs.getString("COLUMN_NAME").lowercase())
        }
        if (columns.isEmpty()) return

        val indices = existingIndices(conn, table)
        val usernameOnly = indices.filter { index ->
            index.unique &&
                index.origin == CREATED_BY_STATEMENT &&
                index.columns.size == 1 &&
                index.columns.single().equals(DownloadedUsers.username.name, ignoreCase = true)
        }

        val missing = DownloadedUsers.indices.filter { index ->
            val expected = index.columns.map { it.name.lowercase() }
            indices.none { existing -> existing.unique == index.unique && existing.columns.map { it?.lowercase() } == expected }
        }

        val statements = transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            buildList {
                if (DownloadedUsers.server.name.lowercase() !in columns) addAll(DownloadedUsers.server.createStatement())
                for (index in usernameOnly) {
                    addAll(Index(listOf(DownloadedUsers.username), unique = true, customName = index.name).dropStatement())
                }
                for (index in missing) {
                    addAll(index.createStatement())
                }
            }
        }

        conn.createStatement().use { statement ->
            for (sql in statements) {
                statement.execute(sql)
            }
        }
    }

    private fun existingIndices(conn: Connection, table: String): List<ExistingIndex> {
        val listed = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(\"$table\")").use { rs ->
                buildList {
                    while (rs.next()) add(Triple(rs.getString("name"), rs.getInt("unique") == 1, rs.getString("origin")))
                }
            }
        }
        return listed.map { (name, unique, origin) ->
            val indexed = conn.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_info(\"$name\")").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
            ExistingIndex(name, unique, origin, indexed)
        }
    }
}
