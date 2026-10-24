package dev.dertyp.synara.migrations

import dev.dertyp.synara.db.LocalDatabaseMigration
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.sqlite.SQLiteDataSource
import java.io.File

enum class DataRisk(val advice: String) {
    POSSIBLE_RENAME(
        "The table gains and loses columns in the same migration. If this is a rename, the generated statements " +
            "add an empty column and drop the old one with its data. " +
            "Replace them by ALTER TABLE ... RENAME COLUMN ... TO ... in the file"
    ),
    REQUIRED_WITHOUT_DEFAULT(
        "The new column is NOT NULL and has no database default. Adding it fails on SQLite. " +
            "Give it a default, or add it nullable and fill it"
    ),
}

class DataRiskWarning(val table: Table, val risk: DataRisk, val added: List<String>, val removed: List<String>) {
    fun describe(): String = buildString {
        append("${table.tableName}: ")
        if (added.isNotEmpty()) append("added ${added.joinToString()}. ")
        if (removed.isNotEmpty()) append("removed ${removed.joinToString()}. ")
        append(risk.advice)
    }
}

class SchemaState(val pending: List<String>, val warnings: List<DataRiskWarning>)

class MigratedDatabase(val file: File) : AutoCloseable {
    val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }
    val database: Database = Database.connect(dataSource)

    fun <T> transaction(block: JdbcTransaction.() -> T): T = transaction(database) { block() }

    override fun close() {
        TransactionManager.closeAndUnregister(database)
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    companion object {
        fun create(): MigratedDatabase =
            MigratedDatabase(File.createTempFile("schema_drift", ".db")).also { LocalDatabaseMigration.migrate(it.dataSource) }
    }
}

object SchemaDrift {
    fun tables(additions: List<Table>): List<Table> =
        SchemaTables.all.filterNot { table -> additions.any { it.tableName == table.tableName } } + additions

    fun inspect(additionalTables: List<Table> = emptyList()): SchemaState =
        inspectEach(listOf(additionalTables)).single()

    fun inspectEach(additions: List<List<Table>>, indicesOnlyInDatabase: List<Index> = emptyList()): List<SchemaState> =
        MigratedDatabase.create().use { migrated ->
            migrated.transaction {
                additions.map { state(tables(it)) } + indicesOnlyInDatabase.map { stateWith(it) }
            }
        }

    private fun JdbcTransaction.stateWith(index: Index): SchemaState {
        index.createStatement().forEach { exec(it) }
        connection.metadata { cleanCache() }
        val state = state(tables(emptyList()))
        index.dropStatement().forEach { exec(it) }
        connection.metadata { cleanCache() }
        return state
    }

    private fun JdbcTransaction.state(tables: List<Table>): SchemaState {
        val required = MigrationUtils
            .statementsRequiredForDatabaseMigration(*tables.toTypedArray(), withLogs = false)
            .distinct()
        return SchemaState(required, dataRiskWarnings(tables))
    }

    private fun JdbcTransaction.dataRiskWarnings(tables: List<Table>): List<DataRiskWarning> {
        val migrated = tables.filter { it.exists() }
        val existing = currentDialectMetadata.tableColumns(*migrated.toTypedArray())
        return migrated.flatMap { table ->
            val present = existing[table].orEmpty().map { it.name }
            val added = table.columns.filter { column -> present.none { it.equals(column.nameUnquoted(), true) } }
            val removed = present.filter { name -> table.columns.none { it.nameUnquoted().equals(name, true) } }
            val required = added.filter {
                !it.columnType.nullable && it.defaultValueInDb() == null && it.columnType !is AutoIncColumnType<*>
            }
            buildList {
                if (added.isNotEmpty() && removed.isNotEmpty()) {
                    add(DataRiskWarning(table, DataRisk.POSSIBLE_RENAME, added.map { it.name }, removed))
                }
                if (required.isNotEmpty()) {
                    add(DataRiskWarning(table, DataRisk.REQUIRED_WITHOUT_DEFAULT, required.map { it.name }, emptyList()))
                }
            }
        }
    }
}
