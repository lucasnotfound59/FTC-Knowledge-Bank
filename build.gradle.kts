plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val verifyPedroExampleCompile=tasks.register<Exec>("verifyPedroExampleCompile") {
    group="verification"
    description="Compiles the canonical Pedro Auto against the pinned Android fixture"
    workingDir(layout.projectDirectory.dir("fixtures/pedro-compile"))
    val wrapper=if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "./gradlew"
    commandLine(wrapper,"clean","compileDebugJavaWithJavac","--no-daemon")
    doFirst {
        val sdk=System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT before verifyPedroExampleCompile")
        environment("ANDROID_HOME",sdk)
    }
}

tasks.register("verifyPedroRelease") {
    group="verification"
    description="Runs all knowledge tests and the pinned Pedro Android compile fixture"
    dependsOn(
        ":modules:domain:test",
        ":modules:knowledge:test",
        ":apps:knowledge-cli:test",
        verifyPedroExampleCompile
    )
}
