plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "koog-agui"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":koog-agui")
include(":koog-agui-ktor")
include(":example:server")
