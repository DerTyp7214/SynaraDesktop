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
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":common-rpc"))
            }
        }
        
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

configurations.all {
    exclude(group = "org.jetbrains.compose.material", module = "material-desktop")
}
