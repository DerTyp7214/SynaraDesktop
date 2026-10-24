package dev.dertyp.synara.migrations

import java.io.File
import kotlin.system.exitProcess

private class GeneratorFailure(message: String) : Exception(message)

private fun generate(root: File, name: String) {
    if (!Regex("[A-Za-z][A-Za-z0-9]*").matches(name)) {
        throw GeneratorFailure("Pass the migration name as -Pname=AddSomething (letters and digits, starting with a letter).")
    }
    val version = (MigrationFiles.versioned(root).map { it.version } + MigrationFiles.baseVersion).max() + 1
    val target = File(MigrationFiles.folder(root), MigrationFiles.fileName(version, name))
    if (target.exists()) throw GeneratorFailure("$target exists already, nothing was written.")

    val state = SchemaDrift.inspect()

    if (state.pending.isEmpty()) {
        println("Nothing to do: the table definitions match the migrations. No file was written.")
        return
    }

    target.writeText(state.pending.joinToString("") { it.trim().removeSuffix(";") + ";\n" })
    println("${state.pending.size} statement(s) written to $target")
    state.pending.forEach { println("  $it;") }
    state.warnings.forEach { println("  WARNING ${it.describe()}") }
}

fun main(args: Array<String>) {
    try {
        generate(File(args[0]), args.getOrElse(1) { "" })
    } catch (failure: GeneratorFailure) {
        System.err.println(failure.message)
        exitProcess(1)
    }
}
