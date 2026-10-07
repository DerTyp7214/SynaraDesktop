package dev.dertyp.synara.db.migration

import dev.dertyp.core.splitAlbumTitleTags
import dev.dertyp.synara.db.DownloadedAlbums
import kotlinx.serialization.json.Json
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V1_11__AlbumTitleTags : BaseJavaMigration() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun migrate(context: Context) {
        val table = DownloadedAlbums.tableName
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

        data class PendingUpdate(val id: Any?, val name: String, val tags: String)

        val pending = mutableListOf<PendingUpdate>()
        conn.prepareStatement("SELECT \"id\", \"name\" FROM \"$table\"").use { select ->
            select.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getObject("id")
                    val name = rs.getString("name") ?: continue
                    val split = name.splitAlbumTitleTags()
                    if (split.title != name || split.tags.isNotEmpty()) {
                        pending += PendingUpdate(id, split.title, json.encodeToString(split.tags))
                    }
                }
            }
        }

        if (pending.isEmpty()) return

        conn.prepareStatement("UPDATE \"$table\" SET \"name\" = ?, \"tags\" = ? WHERE \"id\" = ?").use { update ->
            for (row in pending) {
                update.setString(1, row.name)
                update.setString(2, row.tags)
                update.setObject(3, row.id)
                update.addBatch()
            }
            update.executeBatch()
        }
    }
}
