package org.ftckb.standardizer

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CheckedInputTest {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repository(root:Path):Git=Git.init().setDirectory(root.toFile()).call()
    private fun commit(git:Git) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage("baseline").setAuthor("test","test@example.invalid").call()
    }
    @Test fun `capture retains staged unstaged deletion rename mode and ignored semantics`(@TempDir root:Path) {
        repository(root).use { git ->
            Files.writeString(root.resolve("both.txt"),"baseline\n")
            Files.writeString(root.resolve("delete.txt"),"deleted body\n")
            Files.writeString(root.resolve("old.txt"),"rename body\n")
            Files.writeString(root.resolve("mode.txt"),"mode body\n")
            Files.writeString(root.resolve(".gitignore"),"ignored/\n")
            commit(git)
            Files.writeString(root.resolve("both.txt"),"staged\n")
            git.add().addFilepattern("both.txt").call()
            Files.writeString(root.resolve("both.txt"),"unstaged\n")
            Files.delete(root.resolve("delete.txt"))
            Files.move(root.resolve("old.txt"),root.resolve("new.txt"))
            assertTrue(root.resolve("mode.txt").toFile().setExecutable(true,false))
            Files.createDirectories(root.resolve("ignored"))
            Files.writeString(root.resolve("ignored/private.txt"),"ignored body\n")
            Files.writeString(root.resolve("untracked.txt"),"untracked body\n")
            val capture=Standardizer.captureWorktree(root)
            assertEquals(root.toRealPath().toString(),capture.repositoryIdentity)
            assertEquals(git.repository.resolve("HEAD").name,capture.headBefore)
            assertEquals(capture.headBefore,capture.headAfter)
            assertEquals(Standardizer.worktreeChanges(root),capture.changes)
            assertEquals(setOf("both.txt","delete.txt","old.txt","new.txt","mode.txt","untracked.txt"),capture.changes.map { it.path }.toSet())
            assertEquals(listOf(1 to "staged",1 to "unstaged"),capture.changes.single { it.path=="both.txt" }.addedLines)
            assertTrue(capture.patches[0].contains("+staged"))
            assertTrue(capture.patches[1].contains("-deleted body"))
            assertTrue(capture.patches[1].contains("rename from old.txt"))
            assertTrue(capture.patches[1].contains("new mode 100755"))
            assertFalse(capture.patches.joinToString().contains("ignored body"))
            val deletion=capture.contentDigests.single { it[0]=="delete.txt" }
            assertEquals(listOf("delete.txt","100644",null,hash("deleted body\n".toByteArray()),null),deletion)
        }
    }
    @Test fun `binary tracked and untracked use actual frozen bytes and captures are stable`(@TempDir root:Path) {
        repository(root).use { git ->
            val old=byteArrayOf(0,1,2,3)
            Files.write(root.resolve("binary.bin"),old)
            commit(git)
            val one=byteArrayOf(0,9,8,7)
            Files.write(root.resolve("binary.bin"),one)
            Files.write(root.resolve("untracked.bin"),one)
            val first=Standardizer.captureWorktree(root)
            assertEquals(first,Standardizer.captureWorktree(root))
            assertTrue(first.patches[1].contains("Binary files"))
            assertEquals(hash(one),first.contentDigests.single { it[0]=="binary.bin" }[4])
            assertEquals(hash(old),first.contentDigests.single { it[0]=="binary.bin" }[3])
            assertNull(first.contentDigests.single { it[0]=="untracked.bin" }[3])
            val two=byteArrayOf(0,6,5,4)
            Files.write(root.resolve("binary.bin"),two)
            Files.write(root.resolve("untracked.bin"),two)
            val second=Standardizer.captureWorktree(root)
            assertEquals(first.changes,second.changes)
            assertNotEquals(first.contentDigests,second.contentDigests)
            assertEquals(hash(two),second.contentDigests.single { it[0]=="untracked.bin" }[4])
        }
    }
    @Test fun `index only binary content is preserved when worktree returns to head`(@TempDir root:Path) {
        repository(root).use { git ->
            val baseline=byteArrayOf(0,1)
            Files.write(root.resolve("binary.bin"),baseline)
            commit(git)
            val staged=byteArrayOf(0,2)
            Files.write(root.resolve("binary.bin"),staged)
            git.add().addFilepattern("binary.bin").call()
            Files.write(root.resolve("binary.bin"),baseline)
            val capture=Standardizer.captureWorktree(root)
            assertEquals(1,capture.contentDigests.size)
            assertEquals(hash(staged),capture.contentDigests.single()[4])
            assertTrue(capture.patches[1].isBlank())
            assertEquals(listOf("binary.bin"),capture.changes.map { it.path })
        }
    }
    @Test fun `deleted content differences survive empty added line projections`(@TempDir root:Path) {
        repository(root).use { git ->
            Files.writeString(root.resolve("deleted.txt"),"first deleted body\n")
            commit(git)
            Files.delete(root.resolve("deleted.txt"))
            val first=Standardizer.captureWorktree(root)
            Files.writeString(root.resolve("deleted.txt"),"second deleted body\n")
            commit(git)
            Files.delete(root.resolve("deleted.txt"))
            val second=Standardizer.captureWorktree(root)
            assertEquals(first.changes,second.changes)
            assertNotEquals(first.patches,second.patches)
            assertNotEquals(first.contentDigests,second.contentDigests)
        }
    }
    @Test fun `unborn repository captures nonignored files without fabricated head`(@TempDir root:Path) {
        repository(root).use {
            Files.writeString(root.resolve("new.txt"),"new\n")
            val captured=Standardizer.captureWorktree(root)
            assertNull(captured.headBefore)
            assertNull(captured.headAfter)
            assertEquals(root.toRealPath().toString(),captured.repositoryIdentity)
            assertEquals(listOf(Standardizer.DiffChange("new.txt",listOf(1 to "new"))),captured.changes)
        }
    }
    @Test fun `unchanged large binary keeps original check successful under low heap`(@TempDir root:Path) {
        repository(root).use { git ->
            Files.newOutputStream(root.resolve("large.bin")).use { output ->
                val block=ByteArray(1024*1024)
                repeat(24) { output.write(block) }
            }
            commit(git)
        }
        val classpath=System.getProperty("standardizer.test.classpath","")
        assertTrue(classpath.isNotBlank(),"test runtime classpath must be available to child JVM")
        val output=root.resolve(".git/probe-output.txt")
        val child=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-Xmx64m","-cp",classpath,CheckedInputLowHeapProbe::class.java.name,root.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"low heap capture probe timed out")
            assertEquals(0,child.exitValue(),Files.readString(output))
            assertTrue(Files.readString(output).contains("low-heap-capture=ok"))
        } finally { if (child.isAlive) child.destroyForcibly() }
    }

}

/** Separate JVM prevents the test runner's larger heap from hiding capture regressions. */
object CheckedInputLowHeapProbe {
    @JvmStatic fun main(args:Array<String>) {
        val root=Path.of(args.single())
        val empty=Standardizer.captureWorktree(root)
        check(empty.changes.isEmpty())
        check(empty.repositoryIdentity.isBlank()) // version is unknown after safe budget fallback
        Files.writeString(root.resolve("small-untracked.txt"),"still checked\n")
        val changed=Standardizer.captureWorktree(root)
        check(changed.changes==listOf(Standardizer.DiffChange("small-untracked.txt",listOf(1 to "still checked"))))
        check(changed.repositoryIdentity.isBlank())
        println("low-heap-capture=ok")
    }
}
