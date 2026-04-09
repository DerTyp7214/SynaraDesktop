package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.DownloadedAlbums
import dev.dertyp.synara.db.DownloadedArtists
import dev.dertyp.synara.db.DownloadedSongs
import dev.dertyp.synara.db.DownloadedUserPlaylistSongs
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

class V1_3__MusicBrainzUUID : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val tables = listOf(
            DownloadedSongs,
            DownloadedAlbums,
            DownloadedArtists,
            DownloadedUserPlaylistSongs
        )

        val createStatementsMap = transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
            tables.associate { it.tableName to SchemaUtils.createStatements(it) }
        }

        val conn = context.connection
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = OFF")

            for (tableObject in tables) {
                val tableName = tableObject.tableName
                val oldTable = "${tableName}_old"

                val tableExists = conn.metaData.getTables(null, null, tableName, null).use { it.next() }
                if (!tableExists) continue

                stmt.execute("DROP TABLE IF EXISTS \"$oldTable\"")
                stmt.execute("ALTER TABLE \"$tableName\" RENAME TO \"$oldTable\"")

                val sqls = createStatementsMap[tableName] ?: emptyList()
                for (sql in sqls) {
                    val trimmed = sql.trim()
                    if (trimmed.startsWith("CREATE TABLE", ignoreCase = true) && 
                        (trimmed.contains(" \"$tableName\" ", ignoreCase = true) || 
                         trimmed.contains(" $tableName ", ignoreCase = true))) {
                        stmt.execute(sql)
                    }
                }

                val colNamesOld = getColumnNames(conn, oldTable)
                val colNamesNew = tableObject.columns.map { it.name }
                val commonColumns = colNamesOld.filter { oldCol -> colNamesNew.any { it.equals(oldCol, ignoreCase = true) } }
                
                val placeholders = commonColumns.joinToString(", ") { "?" }
                val insertSql = "INSERT INTO \"$tableName\" (${commonColumns.joinToString(", ") { "\"$it\"" }}) VALUES ($placeholders)"
                
                conn.prepareStatement("SELECT * FROM \"$oldTable\"").use { selectStmt ->
                    selectStmt.executeQuery().use { rs ->
                        conn.prepareStatement(insertSql).use { insertStmt ->
                            while (rs.next()) {
                                commonColumns.forEachIndexed { index, colName ->
                                    val value = rs.getObject(colName)
                                    if (colName.equals("musicBrainzId", ignoreCase = true)) {
                                        when (value) {
                                            is String -> {
                                                try {
                                                    val uuid = UUID.fromString(value)
                                                    insertStmt.setBytes(index + 1, uuidToBytes(uuid))
                                                } catch (_: Exception) {
                                                    insertStmt.setObject(index + 1, null)
                                                }
                                            }
                                            is ByteArray -> {
                                                insertStmt.setBytes(index + 1, value)
                                            }
                                            else -> {
                                                insertStmt.setObject(index + 1, null)
                                            }
                                        }
                                    } else {
                                        insertStmt.setObject(index + 1, value)
                                    }
                                }
                                insertStmt.executeUpdate()
                            }
                        }
                    }
                }

                stmt.execute("DROP TABLE \"$oldTable\"")
            }

            stmt.execute("PRAGMA foreign_keys = ON")
        }
    }

    private fun getColumnNames(conn: java.sql.Connection, tableName: String): List<String> {
        val names = mutableListOf<String>()
        conn.metaData.getColumns(null, null, tableName, null).use { rs ->
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"))
            }
        }
        return names
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }
}
