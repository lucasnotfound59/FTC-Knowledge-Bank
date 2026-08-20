plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":modules:agent-runtime"))
    api(project(":modules:domain"))
    api(project(":modules:knowledge"))
    api(project(":modules:model-provider"))
    api(project(":modules:model-provider-openai-compatible"))
    api(project(":modules:repository-analysis"))
    api(project(":modules:tooling-git"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
