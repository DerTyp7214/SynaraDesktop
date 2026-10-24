package dev.dertyp.synara.db

import dev.dertyp.synara.db.DatabaseNotAtBaseException.Reason
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion

class MigrationBaseCheck(private val flyway: Flyway) {
    fun refusal(): DatabaseNotAtBaseException? {
        val info = flyway.info()
        val applied = info.applied()
        val base = MigrationVersion.fromVersion(MigrationBase.VERSION)
        val baseRow = applied.firstOrNull { it.version == base && !it.state.isFailed }

        val reason = when {
            applied.isEmpty() -> Reason.TABLES_WITHOUT_HISTORY.takeUnless {
                info.infoResult.allSchemasEmpty || historyIsTheOnlyTable()
            }
            applied.any {
                it.state.isFailed && (it.state != MigrationState.FUTURE_FAILED || it.version <= base)
            } -> Reason.FAILED_MIGRATION
            baseRow == null -> Reason.BELOW_BASE
            applied.any {
                it.version != null && it.version > base &&
                    (it.state == MigrationState.MISSING_SUCCESS || it.state == MigrationState.MISSING_FAILED)
            } -> Reason.MISSING_MIGRATION
            else -> null
        } ?: return null

        return DatabaseNotAtBaseException(reason, info.current()?.version?.version)
    }

    private fun historyIsTheOnlyTable(): Boolean = flyway.configuration.dataSource.connection.use { connection ->
        connection.metaData.getTables(connection.catalog, connection.schema, null, arrayOf("TABLE")).use { tables ->
            val names = generateSequence { if (tables.next()) tables.getString("TABLE_NAME") else null }.toList()
            names == listOf(flyway.configuration.table)
        }
    }
}
