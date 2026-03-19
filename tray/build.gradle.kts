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
            }
        }
        jvmMain {
            dependencies {
                implementation(libs.jna)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
