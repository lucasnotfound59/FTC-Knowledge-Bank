pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Auto-provision a missing JDK 21 toolchain (e.g. machines that only have newer
// JDKs) instead of failing with "Cannot find a Java installation matching 21".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name="FTC-Knowledge-Bank"
include(":modules:domain")
include(":modules:knowledge")
include(":modules:model-provider")
include(":modules:model-provider-openai-compatible")
include(":modules:repository-analysis")
include(":modules:tooling-git")
include(":modules:agent-runtime")
include(":apps:knowledge-cli")
