plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("org.ftckb.cli.MainKt") }
tasks.named<JavaExec>("run") { workingDir(rootProject.projectDir) }

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:knowledge"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
