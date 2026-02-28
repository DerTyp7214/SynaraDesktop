import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

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
            packageVersion = "1.0.0"
            packageName = "Synara"

            modules("jdk.unsupported")

            linux {
                shortcut = true
                menuGroup = "Audio"
            }
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