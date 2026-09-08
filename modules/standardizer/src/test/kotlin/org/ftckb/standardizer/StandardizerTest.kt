package org.ftckb.standardizer

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StandardizerTest {
    private fun repository(root:Path):Pair<Path,Git> {
        val repo=Files.createDirectory(root.resolve("repo"))
        Files.writeString(repo.resolve("tracked.txt"),"base\n")
        Files.writeString(repo.resolve("rename me.txt"),"same\n")
        Files.writeString(repo.resolve("delete.txt"),"delete\n")
        val git=Git.init().setDirectory(repo.toFile()).call()
        git.add().addFilepattern(".").call()
        git.commit().setMessage("base").call()
        return repo to git
    }

    @Test
    fun `collects staged unstaged untracked deletion empty and unicode paths`(@TempDir root:Path) {
        val (repo,git)=repository(root)
        git.use {
            Files.writeString(repo.resolve("build.common.gradle"),"staged\n")
            git.add().addFilepattern("build.common.gradle").call()
            Files.delete(repo.resolve("build.common.gradle"))
            Files.writeString(repo.resolve("tracked.txt"),"base\nunstaged\n")
            Files.writeString(repo.resolve("未跟踪 file.txt"),"new\n")
            Files.writeString(repo.resolve("empty.txt"),"")
            Files.delete(repo.resolve("delete.txt"))

            val changes=Standardizer.worktreeChanges(repo).associateBy { it.path }
            assertTrue("build.common.gradle" in changes)
            assertEquals(listOf(2 to "unstaged"),changes.getValue("tracked.txt").addedLines)
            assertEquals(listOf(1 to "new"),changes.getValue("未跟踪 file.txt").addedLines)
            assertTrue("empty.txt" in changes)
            assertTrue("delete.txt" in changes)
        }
    }

    @Test
    fun `pure rename reports both paths and mode only reports path`(@TempDir root:Path) {
        val (repo,git)=repository(root)
        git.use {
            Files.move(repo.resolve("rename me.txt"),repo.resolve("renamed 中文.txt"))
            repo.resolve("tracked.txt").toFile().setExecutable(true,false)
            val paths=Standardizer.worktreeChanges(repo).map { it.path }
            assertTrue("rename me.txt" in paths,paths.toString())
            assertTrue("renamed 中文.txt" in paths,paths.toString())
            assertTrue("tracked.txt" in paths,paths.toString())
        }
    }

    @Test
    fun `parser includes delete and rejects malformed patches`() {
        val deletion="""
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            --- a/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
        """.trimIndent()+"\n"
        assertEquals(listOf(Standardizer.DiffChange("gone.txt",emptyList())),Standardizer.parsePatch(deletion))
        assertThrows(IllegalArgumentException::class.java) { Standardizer.parsePatch("not a patch\n") }
        assertThrows(IllegalArgumentException::class.java) {
            Standardizer.parsePatch("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ broken\n")
        }
    }
}
