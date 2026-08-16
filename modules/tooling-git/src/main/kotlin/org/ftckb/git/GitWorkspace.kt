package org.ftckb.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

data class GitWorkspaceState(
    val repositoryRoot:Path,val branch:String?,val detached:Boolean,val dirtyPaths:Set<String>
)

sealed interface GitBranchState {
    val repositoryRoot:Path

    data class Named(override val repositoryRoot:Path,val branch:String):GitBranchState
    data class Detached(override val repositoryRoot:Path):GitBranchState
}

data class GitFirstTouchResult<T>(
    val value:T,val dirtyPaths:Set<String>,val beforeExecutable:Map<String,Boolean?>
)
internal data class GitHeadState(val fullBranch:String?,val objectId:ObjectId?)

class GitBranchReadException(message:String,cause:Throwable?=null):IOException(message,cause)

object GitWorkspace {
    fun currentBranch(repositoryRoot:Path):GitBranchState=try {
        openSelectedRepository(repositoryRoot).use { repository->
            val root=repository.workTree.toPath().toRealPath()
            val fullBranch=repository.fullBranch
                ?:throw GitBranchReadException("Git HEAD is unavailable")
            if (fullBranch.startsWith(Constants.R_HEADS)) {
                GitBranchState.Named(root,Repository.shortenRefName(fullBranch))
            } else {
                GitBranchState.Detached(root)
            }
        }
    } catch (failure:GitBranchReadException) {
        throw failure
    } catch (failure:Exception) {
        throw GitBranchReadException("Could not read the current Git branch",failure)
    }

    fun inspect(repositoryRoot:Path):GitWorkspaceState {
        openSelectedRepository(repositoryRoot).use { repository->
            val root=repository.workTree.toPath().toRealPath()
            val fullBranch=repository.fullBranch
            val detached=fullBranch==null||!fullBranch.startsWith(Constants.R_HEADS)
            val dirtyPaths=Git.wrap(repository).status().call().let { status->
                (
                    status.added+status.changed+status.modified+status.removed+status.missing+
                        status.untracked+status.conflicting
                    ).toSortedSet()
            }
            return GitWorkspaceState(
                repositoryRoot=root,
                branch=fullBranch?.takeUnless { detached }?.let(Repository::shortenRefName),
                detached=detached,
                dirtyPaths=dirtyPaths
            )
        }
    }

    fun <T> withFirstTouchDirtyPaths(
        repositoryRoot:Path,expectedContents:Map<String,String?>,action:()->T
    ):GitFirstTouchResult<T> =withFirstTouchDirtyPaths(
        repositoryRoot,expectedContents,action
    ) { repository->GitHeadState(repository.fullBranch,repository.resolve(Constants.HEAD)) }

    internal fun <T> withFirstTouchDirtyPaths(
        repositoryRoot:Path,
        expectedContents:Map<String,String?>,
        action:()->T,
        afterActionHeadState:(Repository)->GitHeadState
    ):GitFirstTouchResult<T> {
        require(expectedContents.isNotEmpty()) { "first-touch paths must not be empty" }
        openSelectedRepository(repositoryRoot).use { repository->
            val root=repository.workTree.toPath().toRealPath()
            val branchBefore=repository.fullBranch
            val headBefore=repository.resolve(Constants.HEAD)
            val index=repository.lockDirCache()
            try {
                val inspection=directDirtyPaths(repository,root,index,headBefore,expectedContents)
                require(repository.workTree.toPath().toRealPath()==root) {
                    "Git repository root changed during first-touch inspection"
                }
                require(repository.fullBranch==branchBefore && repository.resolve(Constants.HEAD)==headBefore) {
                    "Git HEAD changed during first-touch inspection"
                }
                val value=action()
                val headChanged=try {
                    val current=afterActionHeadState(repository)
                    current.fullBranch!=branchBefore||current.objectId!=headBefore
                } catch (_:Exception) {
                    true
                }
                if (headChanged) inspection.dirtyPaths.addAll(expectedContents.keys)
                return GitFirstTouchResult(
                    value,inspection.dirtyPaths.toSortedSet(),inspection.beforeExecutable.toSortedMap()
                )
            } finally {
                index.unlock()
            }
        }
    }

    private data class FirstTouchInspection(
        val dirtyPaths:MutableSet<String>,val beforeExecutable:Map<String,Boolean?>
    )

    private data class WorktreeIndexState(val matches:Boolean,val executable:Boolean?)

    private fun directDirtyPaths(
        repository:Repository,root:Path,index:DirCache,head:ObjectId?,expectedContents:Map<String,String?>
    ):FirstTouchInspection {
        val dirty=sortedSetOf<String>()
        val beforeExecutable=sortedMapOf<String,Boolean?>()
        ObjectInserter.Formatter().use { formatter->
            RevWalk(repository).use { walk->
                val headTree=head?.let { walk.parseCommit(it).tree }
                expectedContents.forEach { (path,expectedContent)->
                    val absolute=root.resolve(path).normalize()
                    require(absolute.startsWith(root)) { "first-touch path escapes the repository" }
                    val entry=index.getEntry(path)
                    val worktree=expectedWorktreeMatchesIndex(
                        repository,absolute,expectedContent,entry,formatter
                    )
                    beforeExecutable[path]=worktree.executable
                    if (
                        !headMatchesIndex(repository,headTree,path,entry) ||
                        !worktree.matches
                    ) dirty+=path
                }
            }
        }
        return FirstTouchInspection(dirty,beforeExecutable)
    }

    private fun headMatchesIndex(
        repository:Repository,headTree:org.eclipse.jgit.revwalk.RevTree?,path:String,indexEntry:DirCacheEntry?
    ):Boolean {
        val headEntry=headTree?.let { tree->
            TreeWalk.forPath(repository,path,tree)?.use { walk->
                walk.getObjectId(0).copy() to walk.getFileMode(0)
            }
        }
        if (headEntry==null) return indexEntry==null
        return indexEntry!=null && indexEntry.stage==DirCacheEntry.STAGE_0 &&
            indexEntry.objectId==headEntry.first && indexEntry.fileMode==headEntry.second
    }

    private fun expectedWorktreeMatchesIndex(
        repository:Repository,
        path:Path,
        expectedContent:String?,
        indexEntry:DirCacheEntry?,
        formatter:ObjectInserter.Formatter
    ):WorktreeIndexState {
        if (expectedContent==null) return WorktreeIndexState(indexEntry==null,null)
        val bytes=expectedContent.toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_FIRST_TOUCH_TEXT_BYTES) { "first-touch path exceeds text size limit" }
        val attributes=Files.readAttributes(
            path,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return WorktreeIndexState(false,null)
        val executable=try {
            Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).any { permission->
                permission==PosixFilePermission.OWNER_EXECUTE||
                    permission==PosixFilePermission.GROUP_EXECUTE||
                    permission==PosixFilePermission.OTHERS_EXECUTE
            }
        } catch (_:UnsupportedOperationException) {
            repository.fs.canExecute(path.toFile())
        }
        val mode=if (executable) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
        val matches=indexEntry!=null && indexEntry.stage==DirCacheEntry.STAGE_0 &&
            formatter.idFor(Constants.OBJ_BLOB,bytes)==indexEntry.objectId && mode==indexEntry.fileMode
        return WorktreeIndexState(matches,executable)
    }

    private const val MAX_FIRST_TOUCH_TEXT_BYTES=1_048_576
}

internal fun openSelectedRepository(repositoryRoot:Path):Repository {
    val selected=repositoryRoot.toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(selected)) { "selected repository root must not be a symbolic link" }
    val root=selected.toRealPath()
    val gitEntry=root.resolve(".git")
    require(
        !Files.isSymbolicLink(gitEntry) &&
            (
                Files.isDirectory(gitEntry,LinkOption.NOFOLLOW_LINKS)||
                    Files.isRegularFile(gitEntry,LinkOption.NOFOLLOW_LINKS)
                )
    ) {
        "selected root is not a Git repository"
    }
    val repository=FileRepositoryBuilder().findGitDir(root.toFile()).build()
    val discoveredRoot=runCatching { repository.workTree.toPath().toRealPath() }.getOrNull()
    if (repository.isBare||discoveredRoot!=root) {
        repository.close()
        throw IllegalArgumentException("Git repository discovery escaped the selected root")
    }
    return repository
}
