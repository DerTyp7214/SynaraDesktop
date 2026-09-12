package dev.dertyp.synara.db.migration

import dev.dertyp.synara.db.SettingsSyncKnownEntries
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V1_8__SettingsSyncKnown : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val conn = context.connection
        val table = SettingsSyncKnownEntries.tableName

        val exists = conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            generateSequence { if (rs.next()) rs.getString("TABLE_NAME") else null }
                .any { it.equals(table, ignoreCase = true) }
        }
        if (exists) return

        val createStatements =
            transaction(Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")) {
                SchemaUtils.createStatements(SettingsSyncKnownEntries)
            }.filter { it.contains(table, ignoreCase = true) }

        conn.createStatement().use { statement ->
            for (sql in createStatements) {
                statement.execute(sql)
            }
        }
    }
}
