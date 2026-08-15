package org.ftckb.git

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.charset.CharacterCodingException
import java.nio.file.attribute.PosixFilePermission
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.LockFailedException
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitCommitServiceTest {
    @TempDir
    lateinit var tempDir:Path

    @Test
    fun `commit refuses baseline dirty overlap without changing head or index`() {
        val root=tempDir.resolve("overlap")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","user baseline")
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"user + agent")
            val headBefore=git.repository.resolve("HEAD").name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())

            val error=assertThrows(IllegalArgumentException::class.java) {
                GitCommitService.commit(
                    CommitRequest(
                        root,setOf("TeamCode/Drive.kt"),setOf("TeamCode/Drive.kt"),"agent edit"
                    )
                )
            }

            assertEquals("cannot commit paths that were dirty before Agent edits: TeamCode/Drive.kt",error.message)
            assertEquals(headBefore,git.repository.resolve("HEAD").name)
            assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
        }
    }

    @Test
    fun `commit creates one local commit from exact paths and preserves other staged changes`() {
        val root=tempDir.resolve("exact")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            Files.createDirectories(root.resolve("TeamCode"))
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent baseline")
            Files.writeString(root.resolve("outside.txt"),"outside baseline")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").setAuthor("Test","test@example.com").call()
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            )

            assertEquals(40,sha.length)
            assertEquals(sha,git.repository.resolve("HEAD").name)
            assertEquals("main",git.repository.branch)
            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                assertEquals("agent edit",commit.fullMessage)
                assertEquals("agent edit",blobText(git,commit.tree,"TeamCode/Drive.kt"))
                assertEquals("outside baseline",blobText(git,commit.tree,"outside.txt"))
            }
            assertEquals(setOf("outside.txt"),git.status().call().changed)
        }
    }

    @Test
    fun `commit refuses detached head without changing head or index`() {
        val root=tempDir.resolve("detached")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            val head=git.repository.resolve("HEAD").name
            git.checkout().setName(head).call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")

            assertRefusalWithoutRepositoryMutation(git) {
                GitCommitService.commit(CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit"))
            }
        }
    }

    @Test
    fun `commit refuses blank empty unsafe protected and symbolic-link paths before staging`() {
        val root=tempDir.resolve("unsafe")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            commitFixture(git,root,"TeamCode/Clean.kt","clean")
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            Files.createDirectories(root.resolve("aliases"))
            val external=tempDir.resolve("external.kt")
            Files.writeString(external,"external")
            Files.createSymbolicLink(root.resolve("aliases/Drive.kt"),external)
            val requests=listOf(
                CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"   "),
                CommitRequest(root,emptySet(),emptySet(),"agent edit"),
                CommitRequest(root,setOf(""),emptySet(),"agent edit"),
                CommitRequest(root,setOf("../escape.kt"),emptySet(),"agent edit"),
                CommitRequest(root,setOf(".git/config"),emptySet(),"agent edit"),
                CommitRequest(root,setOf(".env.local"),emptySet(),"agent edit"),
                CommitRequest(root,setOf("secrets/key.pem"),emptySet(),"agent edit"),
                CommitRequest(root,setOf("aliases/Drive.kt"),emptySet(),"agent edit"),
                CommitRequest(root,setOf("TeamCode/Clean.kt"),emptySet(),"agent edit"),
                CommitRequest(root,setOf("TeamCode/Drive.kt","TeamCode/Clean.kt"),emptySet(),"agent edit")
            )

            requests.forEach { request->
                assertRefusalWithoutRepositoryMutation(git) { GitCommitService.commit(request) }
            }
        }
    }

    @Test
    fun `commit stages an exact safe deletion`() {
        val root=tempDir.resolve("deletion")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Old.kt","obsolete")
            Files.delete(root.resolve("TeamCode/Old.kt"))

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/Old.kt"),emptySet(),"remove obsolete file")
            )

            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                assertEquals(null,TreeWalk.forPath(git.repository,"TeamCode/Old.kt",commit.tree))
            }
            assertEquals(emptySet<String>(),git.status().call().uncommittedChanges)
        }
    }

    @Test
    fun `commit stages an exact safe new file`() {
        val root=tempDir.resolve("creation")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Existing.kt","existing")
            Files.writeString(root.resolve("TeamCode/New.kt"),"new Agent file")

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/New.kt"),emptySet(),"add Agent file")
            )

            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                assertEquals("new Agent file",blobText(git,commit.tree,"TeamCode/New.kt"))
            }
            assertEquals(emptySet<String>(),git.status().call().uncommittedChanges)
        }
    }

    @Test
    fun `commit never executes repository hooks`() {
        val root=tempDir.resolve("hooks")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val hook=git.repository.directory.toPath().resolve("hooks/pre-commit")
            Files.createDirectories(hook.parent)
            Files.writeString(hook,"#!/bin/sh\ntouch hook-ran\nexit 1\n")
            makeExecutable(hook)
            val postHook=hook.resolveSibling("post-commit")
            Files.writeString(postHook,"#!/bin/sh\ntouch post-hook-ran\n")
            makeExecutable(postHook)

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            )

            assertEquals(sha,git.repository.resolve("HEAD").name)
            assertEquals(false,Files.exists(root.resolve("hook-ran")))
            assertEquals(false,Files.exists(root.resolve("post-hook-ran")))
        }
    }

    @Test
    fun `commit supports a named unborn current branch`() {
        val root=tempDir.resolve("unborn")
        Git.init().setDirectory(root.toFile()).setInitialBranch("new-project").call().use { git->
            Files.createDirectories(root.resolve("TeamCode"))
            Files.writeString(root.resolve("TeamCode/First.kt"),"first Agent file")

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/First.kt"),emptySet(),"initial Agent edit")
            )

            assertEquals("new-project",git.repository.branch)
            assertEquals(sha,git.repository.resolve("HEAD").name)
            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                assertEquals(0,commit.parentCount)
                assertEquals("first Agent file",blobText(git,commit.tree,"TeamCode/First.kt"))
            }
        }
    }

    @Test
    fun `commit failure restores the exact index and leaves head unchanged`() {
        val root=tempDir.resolve("commit-failure")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val headBefore=git.repository.resolve("HEAD").name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())
            val refLock=git.repository.directory.toPath().resolve("refs/heads/main.lock")
            Files.writeString(refLock,"held")

            try {
                assertThrows(Exception::class.java) {
                    GitCommitService.commit(
                        CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
                    )
                }
                assertEquals(headBefore,git.repository.resolve("HEAD").name)
                assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
                assertEquals(setOf("TeamCode/Drive.kt"),git.status().call().modified)
            } finally {
                Files.deleteIfExists(refLock)
            }
        }
    }

    @Test
    fun `commit refuses index lock contention without staging Agent paths`() {
        val root=tempDir.resolve("index-contention")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val headBefore=git.repository.resolve(Constants.HEAD).name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())
            val indexLock=git.repository.indexFile.toPath().resolveSibling("index.lock")
            Files.writeString(indexLock,"held")

            try {
                assertThrows(Exception::class.java) {
                    GitCommitService.commit(
                        CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
                    )
                }
                assertEquals(headBefore,git.repository.resolve(Constants.HEAD).name)
                assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
            } finally {
                Files.deleteIfExists(indexLock)
            }
            val status=git.status().call()
            assertEquals(setOf("outside.txt"),status.added)
            assertEquals(setOf("TeamCode/Drive.kt"),status.modified)
        }
    }

    @Test
    fun `index publication failure rolls back the authorized ref without publishing Agent staging`() {
        val root=tempDir.resolve("index-failure")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val request=CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            val headBefore=git.repository.resolve(Constants.HEAD).name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())
            val indexLock=git.repository.indexFile.toPath().resolveSibling("index.lock")

            try {
                assertThrows(Exception::class.java) {
                    GitCommitService.commit(request,{}, {
                        Files.delete(indexLock)
                        Files.createDirectory(indexLock)
                    })
                }
                assertEquals(headBefore,git.repository.resolve(Constants.HEAD).name)
                assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
                assertEquals(1,git.log().all().call().count())
            } finally {
                Files.deleteIfExists(indexLock)
            }
            val status=git.status().call()
            assertEquals(setOf("outside.txt"),status.added)
            assertEquals(setOf("TeamCode/Drive.kt"),status.modified)
        }
    }

    @Test
    fun `initial commit index publication failure restores the authorized branch to unborn`() {
        val root=tempDir.resolve("unborn-index-failure")
        Git.init().setDirectory(root.toFile()).setInitialBranch("new-project").call().use { git->
            Files.createDirectories(root.resolve("TeamCode"))
            Files.writeString(root.resolve("TeamCode/First.kt"),"first Agent file")
            val request=CommitRequest(root,setOf("TeamCode/First.kt"),emptySet(),"initial Agent edit")
            val indexPath=git.repository.indexFile.toPath()
            val indexBefore=if (Files.exists(indexPath)) Files.readAllBytes(indexPath) else null
            val indexLock=indexPath.resolveSibling("index.lock")

            try {
                assertThrows(Exception::class.java) {
                    GitCommitService.commit(request,{}, {
                        Files.delete(indexLock)
                        Files.createDirectory(indexLock)
                    })
                }
                assertEquals(null,git.repository.resolve(Constants.HEAD))
                assertEquals("new-project",git.repository.branch)
                if (indexBefore==null) assertEquals(false,Files.exists(indexPath))
                else assertArrayEquals(indexBefore,Files.readAllBytes(indexPath))
            } finally {
                Files.deleteIfExists(indexLock)
            }
            val status=git.status().call()
            assertEquals(setOf("TeamCode/First.kt"),status.untracked)
            assertEquals(emptySet<String>(),status.added)
        }
    }

    @Test
    fun `commit refuses a branch switch after index verification and restores unrelated staged state`() {
        val root=tempDir.resolve("branch-race")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            git.branchCreate().setName("other").call()
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val request=CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            val headBefore=git.repository.resolve(Constants.HEAD).name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())

            assertThrows(IllegalArgumentException::class.java) {
                GitCommitService.commit(request) { repository->
                    repository.updateRef(Constants.HEAD).link(Constants.R_HEADS+"other")
                }
            }

            assertEquals("other",git.repository.branch)
            assertEquals(headBefore,git.repository.resolve(Constants.R_HEADS+"main").name)
            assertEquals(headBefore,git.repository.resolve(Constants.R_HEADS+"other").name)
            assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
            assertEquals(1,git.log().all().call().count())
        }
    }

    @Test
    fun `commit refuses a symbolic HEAD relink after ref publication and rolls the authorized ref back`() {
        val root=tempDir.resolve("late-head-relink")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            git.branchCreate().setName("other").call()
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            Files.writeString(root.resolve("TeamCode/Drive.kt"),"agent edit")
            val request=CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            val headBefore=git.repository.resolve(Constants.HEAD).name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())

            assertThrows(IllegalArgumentException::class.java) {
                GitCommitService.commit(request,{}, { repository->
                    repository.updateRef(Constants.HEAD).link(Constants.R_HEADS+"other")
                })
            }

            assertEquals("other",git.repository.branch)
            assertEquals(headBefore,git.repository.resolve(Constants.R_HEADS+"main").name)
            assertEquals(headBefore,git.repository.resolve(Constants.R_HEADS+"other").name)
            assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
            assertEquals(1,git.log().all().call().count())
        }
    }

    @Test
    fun `commit holds the real index lock across verification and ref publication`() {
        val root=tempDir.resolve("index-race")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.writeString(root.resolve("outside.txt"),"user staged")
            git.add().addFilepattern("outside.txt").call()
            val drive=root.resolve("TeamCode/Drive.kt")
            Files.writeString(drive,"agent edit")
            val request=CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")

            var concurrentFailure:Throwable?=null
            val sha=GitCommitService.commit(request) {
                Files.writeString(drive,"unverified IDE bytes")
                makeExecutable(drive)
                concurrentFailure=runCatching {
                    git.add().addFilepattern("TeamCode/Drive.kt").call()
                }.exceptionOrNull()
            }

            assertTrue(
                generateSequence(concurrentFailure) { it.cause }.any { it is LockFailedException },
                concurrentFailure?.javaClass?.name
            )
            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                assertEquals("agent edit",blobText(git,commit.tree,"TeamCode/Drive.kt"))
                TreeWalk.forPath(git.repository,"TeamCode/Drive.kt",commit.tree).use { tree->
                    assertEquals(FileMode.REGULAR_FILE,requireNotNull(tree).getFileMode(0))
                }
                assertEquals(null,TreeWalk.forPath(git.repository,"outside.txt",commit.tree))
            }
            val status=git.status().call()
            assertEquals(setOf("TeamCode/Drive.kt"),status.modified)
            assertEquals(setOf("outside.txt"),status.added)
            assertEquals(false,Files.exists(git.repository.indexFile.toPath().resolveSibling("index.lock")))
        }
    }

    @Test
    fun `commit preserves the HEAD executable mode when core filemode is disabled`() {
        val root=tempDir.resolve("filemode-disabled")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            val source=root.resolve("TeamCode/Drive.kt")
            Files.createDirectories(source.parent)
            Files.writeString(source,"baseline")
            makeExecutable(source)
            git.add().addFilepattern("TeamCode/Drive.kt").call()
            git.commit().setMessage("fixture").setAuthor("Test","test@example.com").call()
            git.repository.config.apply {
                setBoolean("core",null,"filemode",false)
                save()
            }
            Files.writeString(source,"agent edit")

            val sha=GitCommitService.commit(
                CommitRequest(root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit")
            )

            RevWalk(git.repository).use { walk->
                val commit=walk.parseCommit(git.repository.resolve(sha))
                TreeWalk.forPath(git.repository,"TeamCode/Drive.kt",commit.tree).use { tree->
                    assertEquals(FileMode.EXECUTABLE_FILE,requireNotNull(tree).getFileMode(0))
                }
            }
        }
    }

    @Test
    fun `commit refuses a mode changed after the Agent edit without staging Agent paths`() {
        val root=tempDir.resolve("late-mode-change")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            val source=root.resolve("TeamCode/Drive.kt")
            Files.writeString(source,"agent edit")
            makeExecutable(source)
            val headBefore=git.repository.resolve(Constants.HEAD).name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())

            assertThrows(IllegalArgumentException::class.java) {
                GitCommitService.commit(org.ftckb.git.CommitRequest(
                    root,setOf("TeamCode/Drive.kt"),emptySet(),"agent edit","main",
                    mapOf("TeamCode/Drive.kt" to "agent edit"),
                    mapOf("TeamCode/Drive.kt" to false)
                ))
            }

            assertEquals(headBefore,git.repository.resolve(Constants.HEAD).name)
            assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
            assertEquals(setOf("TeamCode/Drive.kt"),git.status().call().modified)
        }
    }

    @Test
    fun `commit never treats malformed UTF8 as expected absence`() {
        val root=tempDir.resolve("malformed")
        Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().use { git->
            commitFixture(git,root,"TeamCode/Drive.kt","baseline")
            Files.write(root.resolve("TeamCode/Drive.kt"),byteArrayOf(0xff.toByte()))
            val request=org.ftckb.git.CommitRequest(
                root,setOf("TeamCode/Drive.kt"),emptySet(),"delete malformed","main",
                mapOf("TeamCode/Drive.kt" to null),mapOf("TeamCode/Drive.kt" to null)
            )

            val headBefore=git.repository.resolve("HEAD").name
            val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())
            assertThrows(CharacterCodingException::class.java) { GitCommitService.commit(request) }
            assertEquals(headBefore,git.repository.resolve("HEAD").name)
            assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
        }
    }

    private fun commitFixture(git:Git,root:Path,path:String,content:String) {
        val file=root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file,content)
        git.add().addFilepattern(path).call()
        git.commit().setMessage("fixture").setAuthor("Test","test@example.com").call()
    }

    private fun CommitRequest(
        root:Path,paths:Set<String>,baselineDirtyPaths:Set<String>,message:String
    ):org.ftckb.git.CommitRequest {
        val branch=when (val state=GitWorkspace.currentBranch(root)) {
            is GitBranchState.Named -> state.branch
            is GitBranchState.Detached -> "main"
        }
        val expected=paths.associateWith { path->
            val file=root.resolve(path).normalize()
            if (Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) null
            else if (Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) Files.readString(file)
            else null
        }
        val expectedExecutable=expected.mapValues { (path,content)->
            if (content==null) null else Files.isExecutable(root.resolve(path))
        }
        return CommitRequest(root,paths,baselineDirtyPaths,message,branch,expected,expectedExecutable)
    }

    private fun blobText(git:Git,tree:org.eclipse.jgit.revwalk.RevTree,path:String):String {
        val walk=TreeWalk.forPath(git.repository,path,tree)
        requireNotNull(walk) { "missing fixture path: $path" }
        walk.use {
            return String(git.repository.open(it.getObjectId(0)).bytes)
        }
    }

    private fun assertRefusalWithoutRepositoryMutation(git:Git,action:()->Unit) {
        val headBefore=git.repository.resolve("HEAD").name
        val indexBefore=Files.readAllBytes(git.repository.indexFile.toPath())
        assertThrows(IllegalArgumentException::class.java,action)
        assertEquals(headBefore,git.repository.resolve("HEAD").name)
        assertArrayEquals(indexBefore,Files.readAllBytes(git.repository.indexFile.toPath()))
    }

    private fun makeExecutable(path:Path) {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        )
    }
}
