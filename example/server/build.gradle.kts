plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.agui.community.koog.example.MainKt")
}

dependencies {
    implementation(project(":koog-agui-ktor"))
    implementation(libs.koog.llms.all)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.logback.classic)
}
