package dev.dertyp.synara.migrations

import dev.dertyp.synara.db.MigrationBase
import java.io.File

class MigrationFile(val file: File, val version: Int, val name: String)

object MigrationFiles {
    private val versioned = Regex("V1_(\\d+)__(\\w+)\\.sql")
    private val baseFile = "B${MigrationBase.VERSION.replace('.', '_')}__Base.sql"

    val baseVersion: Int = MigrationBase.VERSION.substringAfter('.').toInt()

    val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    val resources: File = File(projectRoot, "synara/src/jvmMain/resources/db/migrations")

    fun folder(root: File): File = File(root, MigrationBase.LOCATION.substringAfterLast('/'))

    fun fileName(version: Int, name: String): String = "V1_${version}__$name.sql"

    fun versioned(root: File): List<MigrationFile> =
        folder(root).listFiles().orEmpty()
            .mapNotNull { file ->
                versioned.matchEntire(file.name)?.let { MigrationFile(file, it.groupValues[1].toInt(), it.groupValues[2]) }
            }
            .sortedBy { it.version }

    fun unexpected(root: File): List<String> =
        folder(root).listFiles().orEmpty()
            .map { it.name }
            .filter { it != baseFile && !versioned.matches(it) }
            .sorted()
}
