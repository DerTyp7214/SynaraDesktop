package dev.dertyp.synara.migrations

import io.github.classgraph.ClassGraph
import org.flywaydb.core.api.migration.JavaMigration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationFilesTest {
    @Test
    fun noClassIsASchemaMigration() {
        val classes = ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp.synara")
            .scan().use { scan ->
                scan.getClassesImplementing(JavaMigration::class.java.name).map { it.name }.sorted()
            }

        assertEquals(
            emptyList(),
            classes,
            "Schema migrations are SQL files written by :synara:generateMigration."
        )
    }

    @Test
    fun migrationVersionsAreUniqueAndAboveTheBase() {
        val versions = MigrationFiles.versioned(MigrationFiles.resources).map { it.version }

        assertEquals(versions.distinct(), versions)
        assertEquals(emptyList(), versions.filter { it <= MigrationFiles.baseVersion })
    }

    @Test
    fun theMigrationFolderHoldsOnlyTheBaseAndVersionedMigrations() {
        assertTrue(MigrationFiles.folder(MigrationFiles.resources).isDirectory)
        assertEquals(emptyList(), MigrationFiles.unexpected(MigrationFiles.resources))
    }
}
