package dev.dertyp.synara.migrations

import io.github.classgraph.ClassGraph
import org.jetbrains.exposed.v1.core.Table

object SchemaTables {
    val all: List<Table> by lazy {
        ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp.synara.db")
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Table::class.java.name)
                    .loadClasses(Table::class.java)
                    .asSequence()
                    .mapNotNull {
                        try {
                            it.kotlin.objectInstance
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .distinct()
                    .sortedBy { it.tableName }
                    .toList()
            }
    }
}
