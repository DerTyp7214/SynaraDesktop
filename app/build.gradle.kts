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
                implementation(libs.material3)
                implementation(libs.kotlinx.coroutines.core)
                
                implementation(libs.lwjgl)
                implementation(libs.lwjgl.glfw)
                implementation(libs.lwjgl.opengl)
                implementation(libs.lwjgl.stb)

                runtimeOnly("org.lwjgl:lwjgl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-glfw::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-opengl::$targetNatives")
                runtimeOnly("org.lwjgl:lwjgl-stb::$targetNatives")

                implementation(project(":common-rpc"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.dertyp.synara.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageVersion = "1.0.0"
        }
    }
}
