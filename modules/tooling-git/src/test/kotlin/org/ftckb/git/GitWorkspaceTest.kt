package org.ftckb.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorkspaceTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `inspect reports named current branch without creating a branch`() {
        val root=tempDir.resolve("repo")
        Git.init().setDirectory(root.toFile()).setInitialBranch("feature/current").call().use { git->
            commit(git,root,"tracked.txt","baseline")
            val branchesBefore=git.branchList().call().map { it.name }

            val state=GitWorkspace.inspect(root)

            assertEquals(root.toRealPath(),state.repositoryRoot)
            assertEquals("feature/current",state.branch)
            assertFalse(state.detached)
            assertEquals(branchesBefore,git.branchList().call().map { it.name })
        }
    }

    @Test
    fun `inspect reports detached head without inventing a branch`() {
        val root=tempDir.resolve("detached")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            val commit=commit(git,root,"tracked.txt","baseline")
            git.checkout().setName(commit.name).call()
            val branchesBefore=git.branchList().call().map { it.name }

            val state=GitWorkspace.inspect(root)

            assertEquals(null,state.branch)
            assertEquals(true,state.detached)
            assertEquals(branchesBefore,git.branchList().call().map { it.name })
        }
    }

    @Test
    fun `inspect returns the union of every dirty path category`() {
        val root=tempDir.resolve("dirty")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            listOf("changed.txt","modified.txt","removed.txt","missing.txt","conflict.txt").forEach { path->
                Files.writeString(root.resolve(path),"baseline")
            }
            git.add().addFilepattern(".").call()
            git.commit().setMessage("baseline").setAuthor("Test","test@example.com").call()
            git.branchCreate().setName("conflicting-change").call()
            git.checkout().setName("conflicting-change").call()
            Files.writeString(root.resolve("conflict.txt"),"branch")
            git.add().addFilepattern("conflict.txt").call()
            val branchCommit=git.commit().setMessage("branch change").setAuthor("Test","test@example.com").call()
            git.checkout().setName("main").call()
            Files.writeString(root.resolve("conflict.txt"),"main")
            git.add().addFilepattern("conflict.txt").call()
            git.commit().setMessage("main change").setAuthor("Test","test@example.com").call()
            assertEquals(CONFLICTING,git.merge().include(branchCommit).call().mergeStatus)

            Files.writeString(root.resolve("added.txt"),"added")
            git.add().addFilepattern("added.txt").call()
            Files.writeString(root.resolve("changed.txt"),"changed")
            git.add().addFilepattern("changed.txt").call()
            Files.writeString(root.resolve("modified.txt"),"modified")
            git.rm().addFilepattern("removed.txt").call()
            Files.delete(root.resolve("missing.txt"))
            Files.writeString(root.resolve("untracked.txt"),"untracked")

            val state=GitWorkspace.inspect(root)

            assertEquals(
                setOf(
                    "added.txt","changed.txt","modified.txt","removed.txt","missing.txt",
                    "untracked.txt","conflict.txt"
                ),
                state.dirtyPaths
            )
        }
    }

    @Test
    fun `inspect refuses repository discovery above the selected root`() {
        val root=tempDir.resolve("bounded")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commit(git,root,"tracked.txt","baseline")
        }
        val nested=Files.createDirectories(root.resolve("nested/project"))

        assertThrows(IllegalArgumentException::class.java) { GitWorkspace.inspect(nested) }
    }

    @Test
    fun `inspect refuses a symbolic link masquerading as Git metadata`() {
        val repository=tempDir.resolve("metadata-source")
        Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call().use { git->
            commit(git,repository,"tracked.txt","baseline")
        }
        val selected=Files.createDirectories(tempDir.resolve("metadata-alias"))
        Files.createSymbolicLink(selected.resolve(".git"),repository.resolve(".git"))

        assertThrows(IllegalArgumentException::class.java) { GitWorkspace.inspect(selected) }
    }

    @Test
    fun `inspect refuses a symbolic-link alias for the selected repository root`() {
        val repository=tempDir.resolve("root-source")
        Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call().use { git->
            commit(git,repository,"tracked.txt","baseline")
        }
        val alias=tempDir.resolve("root-alias")
        Files.createSymbolicLink(alias,repository)

        assertThrows(IllegalArgumentException::class.java) { GitWorkspace.inspect(alias) }
    }

    @Test
    fun `inspect supports a linked-worktree Git file rooted at the selected directory`() {
        val repository=tempDir.resolve("git-file-source")
        val commitId=Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call().use { git->
            commit(git,repository,"tracked.txt","baseline").name
        }
        val selected=Files.createDirectories(tempDir.resolve("git-file-worktree"))
        Files.writeString(selected.resolve("tracked.txt"),"baseline")
        val commonGit=repository.resolve(".git")
        val linkedGit=Files.createDirectories(commonGit.resolve("worktrees/git-file-worktree"))
        Files.writeString(linkedGit.resolve("HEAD"),"ref: refs/heads/linked\n")
        Files.writeString(linkedGit.resolve("commondir"),"../..\n")
        Files.writeString(linkedGit.resolve("gitdir"),"${selected.resolve(".git")}\n")
        Files.copy(commonGit.resolve("index"),linkedGit.resolve("index"))
        Files.writeString(commonGit.resolve("refs/heads/linked"),"$commitId\n")
        Files.writeString(selected.resolve(".git"),"gitdir: $linkedGit\n")

        val state=GitWorkspace.inspect(selected)

        assertEquals(selected.toRealPath(),state.repositoryRoot)
        assertEquals("linked",state.branch)
        assertFalse(state.detached)
    }

    private fun commit(git:Git,root:Path,path:String,content:String)=run {
        val file=root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file,content)
        git.add().addFilepattern(path).call()
        git.commit().setMessage("test fixture").setAuthor("Test","test@example.com").call()
    }
}
