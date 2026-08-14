pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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
include(":apps:knowledge-cli")
