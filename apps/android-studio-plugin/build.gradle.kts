plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.6.1")
    }
    implementation(project(":modules:session-shell"))
}

// v1 bundles its own kotlin-stdlib (the platform does not expose one on the compile
// classpath here). Known tradeoff, see docs/android-studio-plugin.md; revisit if runIde
// shows stdlib conflicts.

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }
}

tasks {
    buildPlugin { archiveBaseName.set("ftckb-as") }
}
