buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
}

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val lwjglNativesClassifier = when {
    osName.contains("win") -> "natives-windows"
    osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm")) "natives-macos-arm64" else "natives-macos"
    else -> "natives-linux"
}
extra["lwjglNativesClassifier"] = lwjglNativesClassifier

tasks.register<Exec>("installGitHooks") {
    group = "help"
    description = "Configures git to use the hooks in the .githooks directory"
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast {
        println("Git hooks path updated to .githooks")
    }
}
