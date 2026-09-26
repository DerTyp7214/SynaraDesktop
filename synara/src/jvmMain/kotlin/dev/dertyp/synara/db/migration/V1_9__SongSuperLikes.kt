package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.DownloadedSongs
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V1_9__SongSuperLikes : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val table = DownloadedSongs.tableName
        val conn = context.connection

        val existing = mutableSetOf<String>()
        conn.metaData.getColumns(null, null, table, null).use { rs ->
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME").lowercase())
        }

        if ("superlikedat" !in existing) {
            conn.createStatement().use { statement ->
                statement.execute("ALTER TABLE \"$table\" ADD COLUMN \"superLikedAt\" INTEGER")
            }
        }
    }
}
