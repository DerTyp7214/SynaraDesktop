package dev.dertyp.synara.db.migration

import dev.dertyp.core.splitTitleTags
import dev.dertyp.synara.db.DownloadedSongs
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V1_7__SongTitleTags : BaseJavaMigration() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun migrate(context: Context) {
        val table = DownloadedSongs.tableName
        val conn = context.connection

        val existing = mutableSetOf<String>()
        conn.metaData.getColumns(null, null, table, null).use { rs ->
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME").lowercase())
        }

        if ("tags" !in existing) {
            conn.createStatement().use { statement ->
                statement.execute("ALTER TABLE \"$table\" ADD COLUMN \"tags\" TEXT NOT NULL DEFAULT '[]'")
            }
        }

        data class PendingUpdate(val id: Any?, val title: String, val tags: String)

        val pending = mutableListOf<PendingUpdate>()
        conn.prepareStatement("SELECT \"id\", \"title\" FROM \"$table\"").use { select ->
            select.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getObject("id")
                    val title = rs.getString("title") ?: continue
                    val split = title.splitTitleTags()
                    if (split.title != title || split.tags.isNotEmpty()) {
                        pending += PendingUpdate(id, split.title, json.encodeToString(split.tags))
                    }
                }
            }
        }

        if (pending.isEmpty()) return

        conn.prepareStatement("UPDATE \"$table\" SET \"title\" = ?, \"tags\" = ? WHERE \"id\" = ?").use { update ->
            for (row in pending) {
                update.setString(1, row.title)
                update.setString(2, row.tags)
                update.setObject(3, row.id)
                update.addBatch()
            }
            update.executeBatch()
        }
    }
}
