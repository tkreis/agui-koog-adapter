plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

description = "AG-UI protocol wire model and Koog agent adapter"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(libs.koog.agents.core)
    api(libs.koog.agents.tools)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
