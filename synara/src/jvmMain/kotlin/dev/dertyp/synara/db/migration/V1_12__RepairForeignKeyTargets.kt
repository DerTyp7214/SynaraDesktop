package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.DownloadedAlbumArtists
import dev.dertyp.synara.db.DownloadedAlbumGenres
import dev.dertyp.synara.db.DownloadedArtistGenres
import dev.dertyp.synara.db.DownloadedArtistMembers
import dev.dertyp.synara.db.DownloadedPlaylistSongs
import dev.dertyp.synara.db.DownloadedSongArtists
import dev.dertyp.synara.db.DownloadedSongGenres
import dev.dertyp.synara.db.DownloadedSongs
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

class V1_12__RepairForeignKeyTargets : BaseJavaMigration() {
    private class Rebuild(
        val table: String,
        val replacement: String,
        val createReplacement: String,
        val columns: List<String>,
        val afterRename: List<String>
    )

    override fun canExecuteInTransaction(): Boolean = false

    override fun migrate(context: Context) {
        val conn = context.connection
        val rebuilds = plan(conn)
        if (rebuilds.isEmpty()) return

        val autoCommit = conn.autoCommit
        conn.autoCommit = true
        val enforced = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rs -> rs.next() && rs.getInt(1) == 1 }
        }
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        try {
            conn.autoCommit = false
            try {
                conn.createStatement().use { statement ->
                    for (rebuild in rebuilds) {
                        val columns = rebuild.columns.joinToString(", ") { "\"$it\"" }
                        statement.execute("DROP TABLE IF EXISTS \"${rebuild.replacement}\"")
                        statement.execute(rebuild.createReplacement)
                        statement.execute("INSERT INTO \"${rebuild.replacement}\" ($columns) SELECT $columns FROM \"${rebuild.table}\"")
                        statement.execute("DROP TABLE \"${rebuild.table}\"")
                        statement.execute("ALTER TABLE \"${rebuild.replacement}\" RENAME TO \"${rebuild.table}\"")
                        for (sql in rebuild.afterRename) statement.execute(sql)
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        } finally {
            conn.autoCommit = true
            if (enforced) conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            conn.autoCommit = autoCommit
        }
    }

    private fun plan(conn: Connection): List<Rebuild> {
        val tables = listOf(
            DownloadedSongs,
            DownloadedSongArtists,
            DownloadedAlbumArtists,
            DownloadedArtistMembers,
            DownloadedPlaylistSongs,
            DownloadedSongGenres,
            DownloadedAlbumGenres,
            DownloadedArtistGenres
        )

        val existing = conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
        }

        val broken = tables.filter { table ->
            table.tableName.lowercase() in existing &&
                conn.metaData.getImportedKeys(null, null, table.tableName).use { rs ->
                    generateSequence { if (rs.next()) rs.getString("PKTABLE_NAME") else null }
                        .any { it.lowercase() !in existing }
                }
        }
        if (broken.isEmpty()) return emptyList()

        val statements = transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            broken.associate { table ->
                val indices = table.indices.flatMap { it.createStatement() }
                table.tableName to Triple("CREATE TABLE IF NOT EXISTS ${identity(table)} (", table.createStatement(), indices)
            }
        }

        return broken.mapNotNull { table ->
            val name = table.tableName
            val (createPrefix, creates, indices) = statements.getValue(name)
            val create = creates.single { it.startsWith(createPrefix) }
            val replacement = "${name}_rebuilt"

            val defined = table.columns.map { it.name.lowercase() }.toSet()
            val columns = buildList {
                conn.metaData.getColumns(null, null, name, null).use { rs ->
                    while (rs.next()) add(rs.getString("COLUMN_NAME"))
                }
            }
            if (columns.any { it.lowercase() !in defined }) return@mapNotNull null

            Rebuild(
                table = name,
                replacement = replacement,
                createReplacement = "CREATE TABLE \"$replacement\" (" + create.removePrefix(createPrefix),
                columns = columns,
                afterRename = indices
            )
        }
    }
}
