import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.material3)
                implementation(libs.components.resources)
                implementation(libs.koin.core)

                implementation(project(":synara"))
                implementation(project(":tray"))
            }
        }

        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.dertyp.classic.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            val synaraVersion = project(":synara").version.toString()
            packageVersion = synaraVersion.split("-")[0]
            packageName = "Synara"
            
            val commonIcon = project(":synara").projectDir.resolve("src/commonMain/resources/icon.png")
            val synaraGeneratedIconsDir = project(":synara").layout.buildDirectory.dir("generated/icons")

            linux {
                iconFile.set(commonIcon)
            }
            windows {
                iconFile.set(synaraGeneratedIconsDir.map { it.file("icon.ico") })
            }
            macOS {
                iconFile.set(synaraGeneratedIconsDir.map { it.file("icon.icns") })
            }

            tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }.configureEach {
                dependsOn(":synara:generateIcons")
            }

            modules("jdk.unsupported", "java.sql", "jdk.security.auth", "java.naming", "java.management", "jdk.management", "jdk.crypto.ec")
        }
    }
}
