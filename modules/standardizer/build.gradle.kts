plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":modules:knowledge"))
    api(project(":modules:domain"))
    implementation(libs.jgit)
    implementation(libs.java.diff.utils)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
