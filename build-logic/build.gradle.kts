plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("reproducibleProguard") {
            id = "synara.reproducible-proguard"
            implementationClass = "dev.dertyp.buildlogic.ReproducibleProguardPlugin"
        }
    }
}
