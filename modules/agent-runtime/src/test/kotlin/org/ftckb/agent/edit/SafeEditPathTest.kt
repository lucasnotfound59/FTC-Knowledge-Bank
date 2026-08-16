package org.ftckb.agent.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SafeEditPathTest {
    @Test
    fun `rejects protected Git paths`(@TempDir root:Path) {
        val paths=SafeEditPath(root)

        assertThrows<IllegalArgumentException> { paths.resolve(".git/config") }
    }

    @Test
    fun `rejects protected basenames directories and unsupported extensions case insensitively`(@TempDir root:Path) {
        val paths=SafeEditPath(root)
        val rejected=listOf(
            ".GIT/config.java",".env",".ENV.local","config/.environment.properties","LOCAL.PROPERTIES",
            "signing.JKS","signing.KeyStore","private.pem","build/Test.java","generated/Test.kt",
            ".idea/workspace.xml","notes.txt"
        )

        rejected.forEach { path ->
            assertThrows<IllegalArgumentException>(path) { paths.resolve(path) }
        }
    }

    @Test
    fun `rejects absolute traversal backslash NUL and missing parent paths`(@TempDir root:Path) {
        val paths=SafeEditPath(root)
        val rejected=listOf(
            "/tmp/Test.java","C:/Test.java","C:Test.java","../Test.java","TeamCode/../Test.java",
            "TeamCode\\Test.java","TeamCode/Test\u0000.java","TeamCode/missing/Test.java"
        )

        rejected.forEach { path ->
            assertThrows<IllegalArgumentException>(path) { paths.resolve(path) }
        }
    }

    @Test
    fun `rejects symbolic link files and parents even when targets stay inside the repository`(@TempDir root:Path) {
        val teamCode=root.resolve("TeamCode")
        val actual=root.resolve("actual")
        Files.createDirectories(teamCode)
        Files.createDirectories(actual)
        Files.writeString(actual.resolve("Actual.java"),"text\n")
        Files.createSymbolicLink(teamCode.resolve("Linked.java"),actual.resolve("Actual.java"))
        Files.createSymbolicLink(teamCode.resolve("linked-parent"),actual)
        val paths=SafeEditPath(root)

        assertThrows<IllegalArgumentException> { paths.resolve("TeamCode/Linked.java") }
        assertThrows<IllegalArgumentException> { paths.resolve("TeamCode/linked-parent/New.java") }
    }

    @Test
    fun `rejects a parent symbolic link that escapes the repository`(@TempDir container:Path) {
        val root=container.resolve("repo")
        val outside=container.resolve("outside")
        Files.createDirectories(root.resolve("TeamCode"))
        Files.createDirectories(outside)
        Files.createSymbolicLink(root.resolve("TeamCode/external"),outside)

        assertThrows<IllegalArgumentException> {
            SafeEditPath(root).resolve("TeamCode/external/Test.java")
        }
    }

    @Test
    fun `allows Phase 1 text extensions and classifies only TeamCode descendants as normal`(@TempDir root:Path) {
        Files.createDirectories(root.resolve("TeamCode"))
        val paths=SafeEditPath(root)
        val approved=listOf("java","kt","gradle","xml","yaml","yml","properties","md")

        approved.forEach { extension ->
            assertEquals(EditScope.NORMAL,paths.resolve("TeamCode/Test.$extension").scope)
        }
        assertEquals(EditScope.NORMAL,paths.resolve("TeamCode/build.gradle.kts").scope)
        assertEquals(EditScope.PROJECT_LEVEL,paths.resolve("build.gradle").scope)
    }
}
