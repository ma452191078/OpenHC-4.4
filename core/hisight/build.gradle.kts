plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
}

dependencies {
    api(project(":core:common"))
}
