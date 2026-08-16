plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:knowledge"))
    implementation(project(":modules:repository-analysis"))
    implementation(project(":modules:model-provider"))
    implementation(project(":modules:tooling-git"))
    implementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
