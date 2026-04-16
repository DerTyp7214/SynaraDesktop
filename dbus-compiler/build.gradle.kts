plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.symbol.processing.api)
    implementation(project(":dbus-api"))
}
