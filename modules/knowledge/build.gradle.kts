plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:domain"))
    implementation(libs.snakeyaml.engine)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
