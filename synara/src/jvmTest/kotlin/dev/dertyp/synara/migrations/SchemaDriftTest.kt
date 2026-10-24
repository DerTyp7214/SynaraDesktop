package dev.dertyp.synara.migrations

import dev.dertyp.synara.db.DownloadedGenres
import dev.dertyp.synara.db.SettingsSyncKnownEntries
import org.jetbrains.exposed.v1.core.Index
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

private object UnmigratedTable : Table("drift_probe") {
    val probeId = integer("probeId")
    val label = varchar("label", 32).nullable()

    override val primaryKey = PrimaryKey(probeId)
}

private object SettingsSyncWithUnmigratedColumn : Table(SettingsSyncKnownEntries.tableName) {
    val key = text(SettingsSyncKnownEntries.key.name)
    val value = text(SettingsSyncKnownEntries.value.name).nullable()
    val version = long(SettingsSyncKnownEntries.version.name)
    val unmigrated = integer("driftProbe").default(0)

    override val primaryKey = PrimaryKey(key)

    init {
        index(false, unmigrated)
    }
}

private object SettingsSyncWithRenamedColumn : Table(SettingsSyncKnownEntries.tableName) {
    val key = text(SettingsSyncKnownEntries.key.name)
    val value = text(SettingsSyncKnownEntries.value.name).nullable()
    val revision = long("revision").default(0)

    override val primaryKey = PrimaryKey(key)
}

private object SettingsSyncWithRequiredColumn : Table(SettingsSyncKnownEntries.tableName) {
    val key = text(SettingsSyncKnownEntries.key.name)
    val value = text(SettingsSyncKnownEntries.value.name).nullable()
    val version = long(SettingsSyncKnownEntries.version.name)
    val required = integer("requiredProbe")
    val optional = integer("optionalProbe").nullable()
    val defaulted = integer("defaultedProbe").default(0)

    override val primaryKey = PrimaryKey(key)
}

class SchemaDriftTest {
    private companion object {
        val additions = listOf(
            null,
            UnmigratedTable,
            SettingsSyncWithUnmigratedColumn,
            SettingsSyncWithRenamedColumn,
            SettingsSyncWithRequiredColumn,
        )
        val undeclaredIndex =
            Index(listOf(SettingsSyncKnownEntries.version), unique = false, customName = "drift_probe_idx")
        val copiedIndex = DownloadedGenres.indices.single().let { declared ->
            Index(declared.columns, unique = declared.unique, customName = "drift_probe_copy")
        }
        val indicesOnlyInDatabase = listOf(undeclaredIndex, copiedIndex)
        val inspections: IdentityHashMap<Any?, SchemaState> by lazy {
            val states = SchemaDrift.inspectEach(additions.map { listOfNotNull(it) }, indicesOnlyInDatabase)
            IdentityHashMap<Any?, SchemaState>().apply {
                (additions + indicesOnlyInDatabase).zip(states).forEach { (addition, state) -> put(addition, state) }
            }
        }
    }

    private fun inspection(addition: Any? = null): SchemaState = inspections.getValue(addition)

    private fun statements(block: JdbcTransaction.() -> List<String>): List<String> =
        MigratedDatabase.create().use { it.transaction(block) }

    @Test
    fun theTableDefinitionsNeedNothingBeyondTheBaseAndItsMigrations() {
        assertEquals(
            emptyList(),
            inspection().pending,
            "The table definitions differ from the migrated schema. Run :synara:generateMigration -Pname=<Name> " +
                "and commit the file it writes."
        )
    }

    @Test
    fun aTableWithoutAMigrationIsDrift() {
        assertEquals(statements { UnmigratedTable.ddl }, inspection(UnmigratedTable).pending)
    }

    @Test
    fun aColumnAndAnIndexWithoutAMigrationAreDrift() {
        val expected = statements {
            SettingsSyncWithUnmigratedColumn.unmigrated.ddl +
                SettingsSyncWithUnmigratedColumn.indices.flatMap { it.createStatement() }
        }

        assertEquals(expected.sorted(), inspection(SettingsSyncWithUnmigratedColumn).pending.sorted())
    }

    @Test
    fun anOrdinaryIndexNoTableDeclaresIsDrift() {
        assertEquals(statements { undeclaredIndex.dropStatement() }, inspection(undeclaredIndex).pending)
    }

    @Test
    fun aSecondIndexOverTheColumnsOfADeclaredOneIsDrift() {
        assertEquals(statements { copiedIndex.dropStatement() }, inspection(copiedIndex).pending)
    }

    @Test
    fun theMigratedSchemaRaisesNoDataRiskWarning() {
        assertEquals(emptyList(), inspection().warnings.map { it.risk })
        assertEquals(emptyList(), inspection(SettingsSyncWithUnmigratedColumn).warnings.map { it.risk })
    }

    @Test
    fun aColumnThatReplacesAnotherOneIsReportedAsAPossibleRename() {
        val warning = inspection(SettingsSyncWithRenamedColumn).warnings.single()

        assertEquals(SettingsSyncWithRenamedColumn, warning.table)
        assertEquals(DataRisk.POSSIBLE_RENAME, warning.risk)
        assertEquals(listOf(SettingsSyncWithRenamedColumn.revision.name), warning.added)
        assertEquals(listOf(SettingsSyncKnownEntries.version.name), warning.removed)
    }

    @Test
    fun aNewRequiredColumnWithoutADatabaseDefaultIsReported() {
        val warning = inspection(SettingsSyncWithRequiredColumn).warnings.single()

        assertEquals(SettingsSyncWithRequiredColumn, warning.table)
        assertEquals(DataRisk.REQUIRED_WITHOUT_DEFAULT, warning.risk)
        assertEquals(listOf(SettingsSyncWithRequiredColumn.required.name), warning.added)
    }
}
