plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("org.ftckb.cli.MainKt") }
tasks.named<JavaExec>("run") { workingDir(rootProject.projectDir) }

dependencies {
    implementation(project(":modules:agent-runtime"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:knowledge"))
    implementation(project(":modules:model-provider"))
    implementation(project(":modules:model-provider-openai-compatible"))
    implementation(project(":modules:repository-analysis"))
    implementation(project(":modules:tooling-git"))
    implementation(libs.jgit)
    implementation(libs.snakeyaml.engine)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jgit)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
