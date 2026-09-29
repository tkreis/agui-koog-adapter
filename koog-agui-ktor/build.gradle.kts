plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

description = "Ktor route that serves a Koog AG-UI agent over Server-Sent Events"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":koog-agui"))
    api(libs.ktor.server.core)
    implementation(libs.kotlin.logging)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
