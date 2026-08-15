package org.ftckb.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

data class GitWorkspaceState(
    val repositoryRoot:Path,val branch:String?,val detached:Boolean,val dirtyPaths:Set<String>
)

sealed interface GitBranchState {
    val repositoryRoot:Path

    data class Named(override val repositoryRoot:Path,val branch:String):GitBranchState
    data class Detached(override val repositoryRoot:Path):GitBranchState
}

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
