package org.ftckb.repository

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FtcProjectDetectorTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `detects a supported FTC project from independent Gradle and OpMode markers`() {
        tempDir.resolve("settings.gradle").writeText("include ':TeamCode'")
        val source=tempDir.resolve("TeamCode/src/main/java/org/example/DriveOp.java")
        source.parent.createDirectories()
        source.writeText("@TeleOp public class DriveOp {}")
        tempDir.resolve("TeamCode/build.gradle").writeText("implementation 'org.firstinspires.ftc:RobotCore:9.0.1'")

        val profile=FtcProjectDetector.detect(tempDir)

        assertTrue(profile.supported)
        assertEquals(setOf("TeamCode"),profile.sourceModules)
        assertTrue(profile.markers.any { it.kind==ProjectMarkerKind.OPMODE_ANNOTATION })
    }

    @Test
    fun `rejects a plain Kotlin project without FTC evidence`() {
        tempDir.resolve("settings.gradle.kts").writeText("rootProject.name=\"plain\"")
        tempDir.resolve("app/src/main/kotlin/Example.kt").apply {
            parent.createDirectories()
            writeText("class Example")
        }

        assertTrue(!FtcProjectDetector.detect(tempDir).supported)
    }

    @Test
    fun `rejects an empty TeamCode directory`() {
        tempDir.resolve("TeamCode").createDirectories()

        assertTrue(!FtcProjectDetector.detect(tempDir).supported)
    }

    @Test
    fun `does not treat a standalone Disabled annotation as FTC evidence`() {
        tempDir.resolve("settings.gradle").writeText("rootProject.name=\"plain\"")
        tempDir.resolve("app/src/main/java/Example.java").apply {
            parent.createDirectories()
            writeText("@Disabled class Example {}")
        }

        assertTrue(!FtcProjectDetector.detect(tempDir).supported)
    }
}
