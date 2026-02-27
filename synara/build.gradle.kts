plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.voyager.navigator)
                implementation(libs.voyager.screenmodel)
                implementation(libs.voyager.transitions)
                implementation(libs.voyager.koin)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.serialization.kotlinx.cbor)
                implementation(libs.kotlinx.rpc.krpc.serialization.cbor)
                implementation(libs.ktor.client.websockets)
                implementation(project(":common-rpc"))
                
                // Persistence
                implementation(libs.multiplatform.settings.no.arg)
                implementation(libs.multiplatform.settings.serialization)
                implementation(libs.kaml)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.okio)

                // Compose Resources
                implementation(libs.runtime)
                implementation(libs.foundation)
                implementation(libs.material3)
                implementation(libs.material.icons.extended)
                implementation(libs.components.resources)
                implementation(libs.ui.tooling.preview)

                // Coil
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
                
                // KMPalette
                implementation(libs.kmpalette.core)
                implementation(libs.kmpalette.androidx.palette)
            }
        }
        
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.swing)

                // Audio (LWJGL OpenAL + FLAC decoding)
                implementation(libs.lwjgl)
                implementation(libs.lwjgl.openal)
                implementation(libs.lwjgl.stb)
                implementation(libs.jflac)

                // Add natives for all desktop platforms
                val platforms = listOf("linux", "windows", "macos", "macos-arm64")
                platforms.forEach { platform ->
                    runtimeOnly("org.lwjgl:lwjgl::natives-$platform")
                    runtimeOnly("org.lwjgl:lwjgl-openal::natives-$platform")
                    runtimeOnly("org.lwjgl:lwjgl-stb::natives-$platform")
                }
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

compose.resources {
    publicResClass = true
}

configurations.all {
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
}
