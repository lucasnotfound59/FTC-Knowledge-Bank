plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:agent-runtime"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:knowledge"))
    implementation(project(":modules:model-provider"))
    implementation(project(":modules:model-provider-openai-compatible"))
    implementation(project(":modules:repository-analysis"))
    implementation(project(":modules:tooling-git"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
