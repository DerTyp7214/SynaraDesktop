plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    sourceSets {
        commonMain {
            dependencies {
                // No dependencies needed for basic annotations
            }
        }
    }
}
