plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// JitPack serves the modules of this repository as `com.github.<user>.<repo>:<module>:<tag or commit>`.
// Publishing under those coordinates keeps the dependency of koog-agui-ktor on koog-agui resolvable there.
val jitpackGroup: String? = System.getenv("GROUP")?.let { group -> System.getenv("ARTIFACT")?.let { "$group.$it" } }
    ?.takeIf { System.getenv("JITPACK") == "true" }

allprojects {
    group = jitpackGroup ?: "com.ag-ui.community"
    version = System.getenv("VERSION")?.takeIf { jitpackGroup != null } ?: "0.1.0-SNAPSHOT"
}

// Shared publication for every module that applies `maven-publish`; each module sets its own `description`.
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(provider { project.description })
                    url.set("https://github.com/tkreis/agui-koog-adapter")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("tkreis")
                            name.set("Thomas Kreis")
                        }
                    }
                    scm {
                        url.set("https://github.com/tkreis/agui-koog-adapter")
                        connection.set("scm:git:https://github.com/tkreis/agui-koog-adapter.git")
                        developerConnection.set("scm:git:ssh://git@github.com/tkreis/agui-koog-adapter.git")
                    }
                }
            }
        }
    }
}
