plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

val prerelease = true
val major = 1
val minor = 0
val patch = 0
val buildMajor = 3
val buildMinor = 19
val buildPatch = 0

if (minor > 9 || patch > 9 || buildMajor > 99 || buildMinor > 99 || buildPatch > 99) {
    throw GradleException("Version component too high: minor($minor), patch($patch), buildMajor($buildMajor), buildMinor($buildMinor), buildPatch($buildPatch)")
}

fun getVersionCode(): Int {
    return (major * 100000000 + minor * 10000000 + patch * 1000000 + if (prerelease) (buildMajor * 10000 + buildMinor * 100 + buildPatch) else 999999).also {
        if (it < 0) throw GradleException("versionCode overflow: $it")
    }
}

fun getVersionName(): String {
    return "$major.$minor.$patch${if (prerelease) "-prerelease$buildMajor.$buildMinor.$buildPatch" else ""}"
}

version = getVersionName()

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":tray"))
                implementation(project(":common-rpc"))
                implementation(project(":dbus-api"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.voyager.navigator)
                implementation(libs.voyager.screenmodel)
                implementation(libs.voyager.transitions)
                implementation(libs.voyager.koin)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.cors)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.serialization.kotlinx.cbor)
                implementation(libs.kotlinx.rpc.krpc.serialization.cbor)
                implementation(libs.ktor.client.websockets)

                // Persistence
                implementation(libs.multiplatform.settings.no.arg)
                implementation(libs.multiplatform.settings.serialization)
                implementation(libs.kaml)
                implementation(libs.kotlinx.serialization.cbor)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.okio)

                // Compose Resources
                implementation(libs.runtime)
                implementation(libs.foundation)
                implementation(libs.material3)
                implementation(libs.material.symbols.rounded)
                implementation(libs.material.symbols.rounded.filled)
                implementation(libs.material.symbols.outlined)
                implementation(libs.material.symbols.outlined.filled)
                implementation(libs.material.symbols.sharp)
                implementation(libs.material.symbols.sharp.filled)
                implementation(libs.icons.lucide)
                implementation(libs.phosphor.icons)
                implementation(libs.components.resources)
                implementation(libs.ui.tooling.preview)

                // Coil
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)

                // KMPalette
                implementation(libs.kmpalette.core)
                implementation(libs.kmpalette.androidx.palette)

                implementation(libs.haze)
                implementation(libs.jcodec)
                implementation(libs.jcodec.javase)
            }

            kotlin.srcDir(
                tasks.register("generateBuildConfig") {
                    val outputDir = layout.buildDirectory.dir("generated/buildConfig/kotlin/main")
                    val isDebugBuild = gradle.startParameter.taskNames.none {
                        it.contains("package", ignoreCase = true) ||
                            it.contains("release", ignoreCase = true) ||
                            it.contains("createDistributable", ignoreCase = true)
                    }
                    val versionName = getVersionName()
                    val versionCode = getVersionCode()
                    val isPrerelease = prerelease
                    inputs.property("isDebug", isDebugBuild)
                    inputs.property("versionName", versionName)
                    inputs.property("versionCode", versionCode)
                    inputs.property("prerelease", isPrerelease)
                    outputs.dir(outputDir)
                    doLast {
                        val configFile = outputDir.get().file("dev/dertyp/synara/BuildConfig.kt").asFile
                        configFile.parentFile.mkdirs()
                        configFile.writeText(
                            """
                            package dev.dertyp.synara

                            /*
                             * This file is generated by the build system.
                             * IS_DEBUG is true for 'run' and local builds, false for packaging/release.
                             */
                            object BuildConfig {
                                const val IS_DEBUG = $isDebugBuild
                                const val VERSION = "$versionName"
                                const val VERSION_CODE = $versionCode
                                const val PRERELEASE = $isPrerelease
                            }
                            """.trimIndent()
                        )
                    }
                }
            )
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.exposed.migration.jdbc)
                implementation(libs.classgraph)
            }
        }

        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.swing)

                // Audio (LWJGL OpenAL + FLAC decoding)
                implementation(libs.lwjgl)
                implementation(libs.lwjgl.opengl)
                implementation(libs.lwjgl.openal)
                implementation(libs.lwjgl.stb)
                implementation(libs.jflac)
                implementation(libs.concentus)
                implementation(libs.vorbis.java)
                implementation(libs.jlayer)

                implementation(libs.hikari)
                implementation(libs.sqlite.jdbc)

                // Exposed
                implementation(libs.exposed.core)
                implementation(libs.exposed.dao)
                implementation(libs.exposed.jdbc)

                // Flyway
                implementation(libs.flyway.core)

                // D-Bus (MPRIS)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.native.unixsocket)
                implementation(libs.zstd)
                implementation(libs.mediasession.kt)
                implementation(libs.jna)

                // Add natives for the current desktop platform only
                val targetNatives = rootProject.extra["lwjglNativesClassifier"] as String
                runtimeOnly("org.lwjgl:lwjgl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-opengl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-openal::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-stb::$targetNatives")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":icon-processor"))
    add("kspJvm", project(":icon-processor"))
    add("kspJvm", project(":dbus-compiler"))
}

compose.resources {
    publicResClass = true
}

configurations.all {
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
    exclude(group = "com.github.hypfvieh", module = "dbus-java-transport-jnr-unixsocket")
}

tasks.register<JavaExec>("generateMigration") {
    group = "build setup"
    description =
        "Writes the next local database migration as an SQL file from what Exposed still needs. Usage: -Pname=AddSomething."
    val testCompilation = kotlin.jvm().compilations.getByName("test")
    dependsOn(testCompilation.compileTaskProvider)
    mainClass.set("dev.dertyp.synara.migrations.MigrationGeneratorKt")
    classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    workingDir = rootProject.projectDir
    args(
        layout.projectDirectory.dir("src/jvmMain/resources/db/migrations").asFile.path,
        providers.gradleProperty("name").getOrElse(""),
    )
}

tasks.register("printReleaseVersion") {
    val releaseVersion = "$buildMajor.$buildMinor.$buildPatch"
    doLast {
        println(releaseVersion)
    }
}

tasks.register("generateIcons") {
    val inputIcon = file("src/commonMain/resources/icon.png")
    val outputDir = layout.buildDirectory.dir("generated/icons")
    inputs.file(inputIcon)
    outputs.dir(outputDir)

    doLast {
        if (!outputDir.get().asFile.exists()) outputDir.get().asFile.mkdirs()

        val icoFile = outputDir.get().file("icon.ico").asFile
        val icnsFile = outputDir.get().file("icon.icns").asFile

        fun runCommand(vararg args: String): Boolean {
            return try {
                providers.exec {
                    commandLine(*args)
                }.result.get().exitValue == 0
            } catch (_: Exception) {
                false
            }
        }

        val os = System.getProperty("os.name").lowercase()
        val isMac = os.contains("mac")

        val magickExec: List<String>? = when {
            runCommand("convert", "--version") -> listOf("convert")
            runCommand("magick", "--version") -> listOf("magick", "convert")
            else -> null
        }

        var iconsGenerated = false
        if (magickExec != null) {
            try {
                providers.exec {
                    commandLine(*magickExec.toTypedArray(), inputIcon.absolutePath, "-define", "icon:auto-resize=16,32,48,64,128,256", icoFile.absolutePath)
                }.result.get()

                providers.exec {
                    commandLine(*magickExec.toTypedArray(), inputIcon.absolutePath, "-define", "icns:auto-resize=16,32,64,128,256,512,1024", icnsFile.absolutePath)
                }.result.get()

                logger.lifecycle("Icons generated successfully using ${magickExec.joinToString(" ")}.")
                iconsGenerated = true
            } catch (e: Exception) {
                logger.warn("ImageMagick conversion failed: ${e.message}")
            }
        }

        if (!iconsGenerated && isMac && runCommand("sips", "--help")) {
            val success = try {
                providers.exec {
                    commandLine("sips", "-s", "format", "icns", inputIcon.absolutePath, "--out", icnsFile.absolutePath)
                }.result.get().exitValue == 0
            } catch (_: Exception) {
                false
            }

            if (success) {
                logger.lifecycle("ICNS generated successfully using sips. ICO skipped.")
                iconsGenerated = true
            } else {
                logger.warn("sips failed to generate ICNS. Please install ImageMagick (e.g. 'brew install imagemagick') for full icon support.")
            }
        }

        if (!iconsGenerated && magickExec == null && (!isMac || !runCommand("sips", "--help"))) {
            logger.error("No image conversion tool found (convert, magick, or sips). Please install ImageMagick.")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                packages("synara.synara.generated.resources")
                classes("dev.dertyp.synara.BuildConfig")
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
    }
}
