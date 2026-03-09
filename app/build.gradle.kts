import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

evaluationDependsOn(":synara")

val osName = System.getProperty("os.name").lowercase()
val targetNatives = when {
    osName.contains("win") -> "natives-windows"
    osName.contains("mac") -> "natives-macos"
    else -> "natives-linux"
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.material3)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                
                implementation(libs.lwjgl)
                implementation(libs.lwjgl.glfw)
                implementation(libs.lwjgl.opengl)
                implementation(libs.lwjgl.stb)
                implementation(libs.skiko.awt)

                implementation(libs.koin.core)

                runtimeOnly("org.lwjgl:lwjgl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-glfw::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-opengl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-stb::$targetNatives")

                implementation(project(":synara"))
            }
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
}

compose.desktop {
    application {
        mainClass = "dev.dertyp.synara.MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            
            val synaraVersion = project(":synara").version.toString()
            packageVersion = synaraVersion.split("-")[0]
            packageName = "Synara"

            val resourcesDir = project(":synara").projectDir.resolve("src/commonMain/resources")
            val commonIcon = resourcesDir.resolve("icon.png")
            val synaraGeneratedIconsDir = project(":synara").layout.buildDirectory.dir("generated/icons")
            
            linux {
                shortcut = true
                menuGroup = "Audio"
                iconFile.set(commonIcon)
            }
            windows {
                iconFile.set(synaraGeneratedIconsDir.map { it.file("icon.ico") })
            }
            macOS {
                bundleID = "dev.dertyp.synara"
                iconFile.set(synaraGeneratedIconsDir.map { it.file("icon.icns") })
            }

            tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") }.configureEach {
                dependsOn(":synara:generateIcons")
            }

            modules("jdk.unsupported", "java.sql", "jdk.security.auth", "java.naming")
        }
    }
}

allprojects {
    tasks.withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/INDEX.LIST")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
}
