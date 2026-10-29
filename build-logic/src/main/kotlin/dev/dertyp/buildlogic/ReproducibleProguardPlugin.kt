package dev.dertyp.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask

class ReproducibleProguardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.withType(AbstractProguardTask::class.java).configureEach {
            doLast {
                val destination = destinationDir.get().asFile
                val main = mainJar.get().asFile
                val outputs = inputFiles.files
                    .filter { it.extension == "jar" && it != main }
                    .associateWith { destination.resolve("${it.nameWithoutExtension}-${it.composeContentHash()}.jar") } +
                    (main to destination.resolve(main.name))
                outputs.forEach { (input, output) ->
                    check(output.isFile) { "ProGuard output $output for $input is missing" }
                    if (input.holdsShrunkPackages()) {
                        output.normalizeJarEntries()
                    } else {
                        input.copyTo(output, overwrite = true)
                    }
                }
            }
        }
    }
}
