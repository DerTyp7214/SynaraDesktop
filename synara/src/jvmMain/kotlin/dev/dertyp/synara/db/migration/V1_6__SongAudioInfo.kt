package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.DownloadedSongs
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V1_6__SongAudioInfo : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val table = DownloadedSongs.tableName
        val conn = context.connection

        val existing = mutableSetOf<String>()
        conn.metaData.getColumns(null, null, table, null).use { rs ->
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME").lowercase())
        }

        val additions = listOf(
            "codec" to "TEXT NOT NULL DEFAULT ''",
            "channels" to "INT NOT NULL DEFAULT 0",
            "audioStartMs" to "BIGINT NULL",
        )

        conn.createStatement().use { statement ->
            for ((name, definition) in additions) {
                if (name.lowercase() in existing) continue
                statement.execute("ALTER TABLE \"$table\" ADD COLUMN \"$name\" $definition")
            }
        }
    }
}
